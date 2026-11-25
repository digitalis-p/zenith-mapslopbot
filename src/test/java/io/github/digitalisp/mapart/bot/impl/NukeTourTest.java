package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tour over lane segments ({@code build.nukeTour}, {@link NukeSweep#tour}) instead of the serpentine, on sparse
 * art as live j0015 on soul sand ({@link NukeMineMovingTest#soulSand}: Soul Speed, one START per tick, every block
 * mined, 2b2t latency, the walk ending near the north-east corner): every target covered once and in reach of its
 * segment's line, every rule of {@link NukeRunTest.Result#assertRules} (Grim FarBreak among them), faster on sparse
 * tiles, not slower on dense ones, the serpentine kept for full ones, lane-end pauses on a segment's end, the edge
 * guard, mining on the move. Synthetic tiles.
 */
class NukeTourTest {
    static final int Y = NukeRunTest.Y;
    static final double[] NE = NukeSweep.corner("NE", 0, 0, 128);
    /** The entry corner's stand ({@code build.nukeEntryCorner}, 3 cells inside the north-east corner). */
    static final double ENTRY_X = 124.5;
    static final double ENTRY_Z = 3.5;

    static NukeRunTest.Scenario scenario(String name, Map<Pos, BlockType> blocks, boolean tour) {
        NukeRunTest.Scenario sc = NukeMineMovingTest.soulSand(name, new LinkedHashMap<>(blocks), false, 0.49);
        sc.endX = NE[0];
        sc.endZ = NE[1];
        sc.tour = tour;
        return sc;
    }

    private static void allBrokenByTheRules(NukeRunTest.Result r) {
        String line = r.run().summaryLine("done");
        assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
        assertEquals(r.targets().size(), r.run().done(), r.name() + ": " + line);
        r.assertRules(1);
        assertEquals(0, r.run().dig().aborted, r.name() + ": " + line);
        assertEquals(0, r.run().watchdogTrips(), r.name() + ": " + line);
    }

    /** Ticks of the run plus the walk from where it ended to the north-east corner (the way off) at Soul Speed. */
    private static double withWayOff(NukeRunTest.Result r) {
        return r.sim().ticks + Math.hypot(r.sim().x - NE[0], r.sim().z - NE[1]) / 0.49;
    }

    private static boolean routeLogged(NukeRunTest.Result r, String what) {
        return r.sim().log.stream().anyMatch(l -> l.startsWith("Fast nuke: " + what));
    }

    // ------------------------------------------------------------------------------------------------ the plan

    /**
     * Each target is in exactly one segment's cover, across within the lane's half width of its line and along within
     * the trim of the segment's ends; the waypoints run segment by segment; the lane ends are the segments' last
     * waypoints but the last one's.
     */
    @Test
    void everyTargetIsCoveredOnceByASegmentInReach() {
        for (long seed = 1; seed <= 4; seed++) {
            Map<Pos, BlockType> tile = NukeMineMovingTest.cobbled(seed, 25, 150);
            for (NukeSweep.Axis axis : NukeSweep.Axis.values()) {
                String what = "seed " + seed + " " + axis;
                NukeSweep.Plan t = NukeSweep.tour(tile.keySet(), axis, 4, 2, 9, 63.5, 63.5, NE[0], NE[1]);
                assertTrue(t.isTour(), what);
                List<Set<Pos>> covers = t.covers();
                int[] seg = t.segmentOf();
                assertNotNull(covers);
                assertNotNull(seg);
                assertEquals(t.lanes().size(), covers.size(), what);
                Set<Pos> seen = new HashSet<>();
                for (int k = 0; k < covers.size(); k++) {
                    NukeSweep.Lane l = t.lanes().get(k);
                    int lo = Math.min(l.from(), l.to()) - 2;
                    int hi = Math.max(l.from(), l.to()) + 2;
                    assertFalse(covers.get(k).isEmpty(), what + ": an empty segment " + k);
                    for (Pos p : covers.get(k)) {
                        assertTrue(seen.add(p), what + ": covered twice " + p);
                        int u = axis == NukeSweep.Axis.Z ? p.x() : p.z();
                        int v = axis == NukeSweep.Axis.Z ? p.z() : p.x();
                        assertTrue(Math.abs(u - l.line()) <= 4, what + ": " + p + " off the line of " + l);
                        assertTrue(v >= lo && v <= hi, what + ": " + p + " beyond the ends of " + l);
                    }
                }
                assertEquals(tile.keySet(), seen, what + ": every target covered");
                assertEquals(t.waypoints().size(), seg.length, what);
                for (int i = 0; i < seg.length; i++) {
                    assertTrue(i == 0 ? seg[i] == 0 : seg[i] == seg[i - 1] || seg[i] == seg[i - 1] + 1, what);
                }
                assertEquals(covers.size() - 1, t.laneEnds().size(), what + ": one lane end per segment but the last");
                for (int i : t.laneEnds()) assertEquals(seg[i] + 1, seg[i + 1], what);
            }
        }
    }

    /** On sparse art the tour's whole walk (to the start, the tour, its end to the corner) is shorter than the sweep's. */
    @Test
    void theTourWalksLessThanTheSweepOnSparseArt() {
        for (long seed = 11; seed <= 16; seed++) {
            Map<Pos, BlockType> tile = NukeMineMovingTest.cobbled(seed, 20, 120);
            for (NukeSweep.Axis axis : NukeSweep.Axis.values()) {
                NukeSweep.Plan s = NukeSweep.plan(tile.keySet(), axis, 4, 2, 63.5, 63.5, NE[0], NE[1]);
                NukeSweep.Plan t = NukeSweep.tour(tile.keySet(), axis, 4, 2, 9, 63.5, 63.5, NE[0], NE[1]);
                double sw = s.walk(63.5, 63.5, NE[0], NE[1]);
                double tw = t.walk(63.5, 63.5, NE[0], NE[1]);
                System.out.printf(Locale.ROOT, "seed %d %s: sweep %.0f blocks, tour %.0f%n", seed, axis, sw, tw);
                assertTrue(tw < sw * 0.85, "seed " + seed + " " + axis + ": " + tw + " vs " + sw);
            }
        }
        assertEquals(0, NukeSweep.tour(List.of(), NukeSweep.Axis.Z, 4, 2, 9, 0, 0, Double.NaN, Double.NaN)
            .waypoints().size());
    }

    // ---------------------------------------------------------------------------------------------- whole runs

    /**
     * Three j0015-like tiles (light, average, heavy): the tour breaks everything by the same rules, from the map
     * station and from the entry corner, and the whole NUKE walk (with the walk on to the corner) takes a tenth less.
     */
    @Test
    void sparseTilesAreBrokenFasterByTheTour() {
        int[][] tiles = {{8, 60}, {20, 120}, {40, 200}};
        List<String> table = new ArrayList<>();
        for (int i = 0; i < tiles.length; i++) {
            Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(31 + i, tiles[i][0], tiles[i][1]);
            blocks.remove(new Pos((int) ENTRY_X, Y, (int) ENTRY_Z));
            NukeRunTest.Result sweep = scenario(blocks.size() + " cobbled, sweep", blocks, false).run();
            NukeRunTest.Result tour = scenario(blocks.size() + " cobbled, tour", blocks, true).run();
            NukeRunTest.Scenario fromCorner = scenario(blocks.size() + " cobbled, tour from the corner", blocks, true);
            fromCorner.startX = ENTRY_X;
            fromCorner.startZ = ENTRY_Z;
            NukeRunTest.Result corner = fromCorner.run();
            for (NukeRunTest.Result r : List.of(sweep, tour, corner)) {
                System.out.println(r.line() + "; " + r.run().summaryLine("done").replaceAll(".*; sweep", "sweep"));
                allBrokenByTheRules(r);
            }
            assertTrue(routeLogged(tour, "tour of "), tour.sim().log.toString());
            assertTrue(tour.run().summaryLine("x").contains("a tour of lane segments"), tour.run().summaryLine("x"));
            assertTrue(withWayOff(tour) < withWayOff(sweep) * 0.9, tour.line() + " vs " + sweep.line());
            assertTrue(withWayOff(corner) < withWayOff(sweep) * 0.9, corner.line() + " vs " + sweep.line());
            table.add(String.format(Locale.ROOT, "%4d blocks: sweep %6.0f, tour %6.0f, from the corner %6.0f ticks"
                    + " (with the walk to NE; %.0f s less)", blocks.size(), withWayOff(sweep), withWayOff(tour),
                withWayOff(corner), (withWayOff(sweep) - withWayOff(tour)) / 20));
        }
        table.forEach(System.out::println);
    }

    /**
     * j0015's densest tiles (4,180-5,523 cobbled deepslate, a quarter to a third of the tile, in large patches): the
     * tour is no slower.
     */
    @Test
    void aDenseCobbledTileIsNotSlower() {
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        Random r = new Random(77);
        for (int i = 0; i < 60; i++) {
            int cx = r.nextInt(128);
            int cz = r.nextInt(128);
            int rad = 4 + r.nextInt(8);
            for (int x = Math.max(0, cx - rad); x <= Math.min(127, cx + rad); x++) {
                for (int z = Math.max(0, cz - rad); z <= Math.min(127, cz + rad); z++) {
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= rad * rad && r.nextDouble() < 0.7) {
                        blocks.put(new Pos(x, Y, z), NukeMineMovingTest.COBBLED_DEEPSLATE);
                    }
                }
            }
        }
        blocks.remove(new Pos(63, Y, 63));
        double density = NukeSweep.density(blocks.keySet());
        assertTrue(density > 0.15 && density < 0.5, "density " + density);
        NukeRunTest.Result sweep = scenario(blocks.size() + " dense, sweep", blocks, false).run();
        NukeRunTest.Result tour = scenario(blocks.size() + " dense, tour", blocks, true).run();
        System.out.println(sweep.line());
        System.out.println(tour.line() + "; " + tour.sim().log.stream().filter(l -> l.startsWith("Fast nuke: "))
            .findFirst().orElse(""));
        allBrokenByTheRules(sweep);
        allBrokenByTheRules(tour);
        assertTrue(withWayOff(tour) <= withWayOff(sweep), tour.line() + " vs " + sweep.line());
    }

    /** An ellen-like terracotta tile (60 % of it) keeps the serpentine with the switch on: the same plan, run and time. */
    @Test
    void aFullTileKeepsTheSerpentine() {
        Map<Pos, BlockType> blocks = NukeRunTest.ellenLike(3);
        blocks.keySet().removeIf(p -> p.x() >= 64 || p.z() >= 64);
        NukeRunTest.Scenario a = new NukeRunTest.Scenario("full, switch off");
        a.blocks = blocks;
        NukeRunTest.Scenario b = new NukeRunTest.Scenario("full, switch on");
        b.blocks = blocks;
        b.tour = true;
        NukeRunTest.Result off = a.run();
        NukeRunTest.Result on = b.run();
        NukeSweep.Plan po = off.run().plan();
        NukeSweep.Plan pn = on.run().plan();
        assertNotNull(po);
        assertNotNull(pn);
        assertFalse(pn.isTour());
        assertEquals(po.lanes(), pn.lanes());
        assertEquals(off.sim().ticks, on.sim().ticks, on.line() + " vs " + off.line());
        assertTrue(routeLogged(on, "sweep of "), on.sim().log.toString());
        assertTrue(on.sim().log.stream().anyMatch(l -> l.contains("above the tour's 50 %")), on.sim().log.toString());
        assertFalse(routeLogged(off, ""), "no route line without the switch: " + off.sim().log);
        on.assertRules(4);
    }

    /** A tour not shorter by the margin keeps the serpentine (one lane of targets: both are the same walk). */
    @Test
    void aTourNoShorterKeepsTheSerpentine() {
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        for (int z = 0; z < 128; z++) {
            for (int x = 0; x < 9; x++) if ((x + z) % 3 == 0) blocks.put(new Pos(x, Y, z), NukeMineMovingTest.COBBLED_DEEPSLATE);
        }
        blocks.remove(new Pos(4, Y, 0));
        NukeRunTest.Scenario sc = scenario("one lane", blocks, true);
        sc.startX = 4.5;
        sc.startZ = 0.5;
        NukeRunTest.Result r = sc.run();
        allBrokenByTheRules(r);
        NukeSweep.Plan pl = r.run().plan();
        assertNotNull(pl);
        assertFalse(pl.isTour(), r.sim().log.toString());
        assertTrue(r.sim().log.stream().anyMatch(l -> l.startsWith("Fast nuke: sweep of") && l.contains("a tour would")),
            r.sim().log.toString());
    }

    /** Segments whose targets all broke before the walk got there (from a join or the next line) are not walked. */
    @Test
    void servedSegmentsAreSkipped() {
        Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(5, 30, 160);
        NukeRunTest.Result r = scenario("skips", blocks, true).run();
        allBrokenByTheRules(r);
        String line = r.run().summaryLine("x");
        int skipped = Integer.parseInt(line.replaceAll(".*a tour of lane segments, (\\d+) skipped.*", "$1"));
        System.out.println(r.line() + "; " + line.replaceAll(".*; sweep", "sweep"));
        assertTrue(skipped > 0, line);
    }

    // ------------------------------------------------------------------------------------------- the other rules

    /**
     * Lane-end pauses for mends stop where a segment ends (its last waypoint, or where the walk turned to the next
     * segment because the rest of this one had broken), nothing in flight, until the tile is done; the question comes
     * at least once a segment the walk breaks on.
     */
    @Test
    void lanEndPausesStopOnASegmentEnd() {
        Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(51, 30, 150);
        NukeRunTest.Scenario sc = scenario("pauses, tour", blocks, true);
        int[] asked = {0};
        sc.pause = sim -> () -> ++asked[0] % 3 == 0 ? "netherite_pickaxe due for mending" : null;
        NukeRunTest.Result r = sc.run();
        NukeSim sim = r.sim();
        NukeRun<NukeSim.Shot> run = r.run();
        TaskStatus st = r.status();
        int pauses = 0;
        int atTurns = 0;
        while (st != TaskStatus.SUCCESS) {
            assertEquals(TaskStatus.FAILED, st, run.failure());
            assertTrue(run.failure().contains(BotActions.BREAK_PAUSED), run.failure());
            NukeSweep.Plan plan = run.plan();
            assertNotNull(plan);
            assertTrue(plan.isTour(), "a tour: " + sim.log);
            double fx = sim.x;
            double fz = sim.z;
            boolean atEnd = plan.laneEnds().stream().anyMatch(i -> Math.hypot(plan.waypoints().get(i)[0] - fx,
                plan.waypoints().get(i)[1] - fz) < 0.6);
            double[] turn = run.turnedAt();
            boolean atTurn = turn != null && Math.hypot(turn[0] - fx, turn[1] - fz) < 1.5;
            if (!atEnd && atTurn) atTurns++;
            assertTrue(atEnd || atTurn, "pause " + pauses + " at a segment end: " + fx + ", " + fz);
            assertTrue(run.canPark(), "nothing in flight at the pause");
            assertTrue(pauses++ < 60, "too many pauses");
            List<Pos> rest = new ArrayList<>(r.server().world.keySet());
            run = new NukeRun<>(rest, sc.params(), sim, sim::broken, sc.pause.apply(sim));
            st = sim.runFrom(run, sim.ticks, 200_000);
        }
        System.out.println("pauses, tour: " + pauses + " pauses (" + atTurns + " where the walk turned), " + asked[0]
            + " questions, " + sim.ticks + " ticks");
        assertTrue(pauses >= 5, "paused " + pauses);
        assertTrue(atTurns > 0, "a pause where the walk turned to the next segment");
        assertEquals(0, r.server().world.size(), "the whole tile broken");
        r.assertRules(1);
    }

    /** Without a pause the tour is asked at every segment it breaks on: as often as its segments, nearly. */
    @Test
    void theTourIsAskedAtEverySegmentItBreaksOn() {
        Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(52, 30, 150);
        NukeRunTest.Scenario sc = scenario("asked, tour", blocks, true);
        int[] asked = {0};
        sc.pause = sim -> () -> {
            asked[0]++;
            return null;
        };
        NukeRunTest.Result r = sc.run();
        allBrokenByTheRules(r);
        NukeSweep.Plan plan = r.run().plan();
        assertNotNull(plan);
        assertTrue(plan.isTour());
        String line = r.run().summaryLine("x");
        int skipped = Integer.parseInt(line.replaceAll(".*a tour of lane segments, (\\d+) skipped.*", "$1"));
        int walked = plan.lanes().size() - skipped;
        System.out.println("asked, tour: " + asked[0] + " questions, " + plan.lanes().size() + " segments, " + skipped
            + " skipped whole");
        assertTrue(asked[0] >= walked - 2, asked[0] + " questions for " + walked + " segments walked");
    }

    /** The live east edge ({@link NukeEdgeGuardTest}) with sparse art and the tour: never in the zone over the trench. */
    @Test
    void theEdgeGuardKeepsTheTourOutOfTheZone() {
        for (double yaw : new double[]{8, -8, 20}) {
            NukeRunTest.Scenario s = NukeEdgeGuardTest.eastEdge("edge, tour, yaw " + yaw, false, yaw);
            Map<Pos, BlockType> art = new LinkedHashMap<>();
            Random r = new Random(7);
            for (Pos p : s.blocks.keySet()) if (r.nextDouble() < 0.06) art.put(p, NukeMineMovingTest.COBBLED_DEEPSLATE);
            s.blocks = art;
            s.tour = true;
            NukeRunTest.Result res = s.run();
            System.out.println(res.line() + "; " + res.run().summaryLine("done").replaceAll(".*; sweep", "sweep"));
            assertEquals(TaskStatus.SUCCESS, res.status(), res.name() + ": " + res.sim().log);
            assertEquals(res.targets().size(), res.run().done(), res.name());
            assertEquals(0, res.sim().hazardTicks, res.name() + ": in the zone at " + res.sim().firstHazard);
            res.assertRules(1);
        }
    }

    /** {@code build.nukeMineMoving} with the tour: the same rules, and no slower than the tour alone. */
    @Test
    void miningOnTheMoveWithTheTourKeepsTheRules() {
        Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(41, 20, 120);
        NukeRunTest.Result plain = scenario("tour", blocks, true).run();
        NukeRunTest.Scenario sc = scenario("tour, on the move", blocks, true);
        sc.mineMoving = true;
        NukeRunTest.Result moving = sc.run();
        System.out.println(plain.line());
        System.out.println(moving.line());
        allBrokenByTheRules(plain);
        allBrokenByTheRules(moving);
        assertTrue(moving.run().summaryLine("x").contains("a tour of lane segments"), moving.run().summaryLine("x"));
        assertTrue(moving.sim().ticks < plain.sim().ticks, moving.line() + " vs " + plain.line());
    }

    /** Without the switch (and the 19-argument Params) the plan is the serpentine, as before. */
    @Test
    void withoutTheSwitchThePlanIsTheSerpentine() {
        Map<Pos, BlockType> blocks = NukeMineMovingTest.cobbled(62, 40, 200);
        NukeRunTest.Scenario sc = scenario("switch off", blocks, false);
        NukeRunTest.Result r = sc.run();
        NukeSweep.Plan pl = r.run().plan();
        assertNotNull(pl);
        assertFalse(pl.isTour());
        NukeSweep.Plan s = NukeSweep.plan(r.targets(), pl.axis(), 4, 2, 63.5, 63.5, NE[0], NE[1]);
        assertEquals(s.lanes(), pl.lanes());
        NukeRun.Params p = sc.params();
        NukeRun.Params old = new NukeRun.Params(p.perTick(), p.maxDigsPerSecond(), p.reach(), p.floor(), p.stopDelay(),
            p.sweep(), p.laneHalfWidth(), p.sweepMinTargets(), p.axis(), p.swingEachStart(), p.hasteCovered(),
            p.watchdogTicks(), p.pairSlow(), p.pocketGuard(), p.artCleared(), p.wayBreak(), p.mineMoving(), p.endX(),
            p.endZ());
        assertFalse(old.tour());
    }
}
