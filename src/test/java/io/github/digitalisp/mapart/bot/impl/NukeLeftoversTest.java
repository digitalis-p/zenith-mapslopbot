package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tail of the fast nuke on soul sand, as live j0024 (3,711 blocks, 1,631 of them cobbled deepslate): the leftover
 * route ({@code build.nukeLeftoverRoute}, {@link NukeLeftovers}) instead of a Baritone walk to each block the sweep
 * left (51 in 47 walks, 1,173 ticks), and the wait for an instant block's late air ({@code build.nukeInstantAckTicks})
 * instead of a refusal and a second START (95 refused, about 83 of them broken by their first START after all). The
 * planner alone, then whole runs in the Sim ({@link NukeMineMovingTest#soulSand}: Soul Speed, one START per tick, 2b2t
 * latency, the end near the north-east corner) with the server's late air ({@link DigServer#lateAir}) and Baritone's
 * start-up ({@link NukeSim#baritoneStartTicks}), by every rule of {@link NukeRunTest.Result#assertRules}.
 */
class NukeLeftoversTest {
    static final int Y = NukeRunTest.Y;
    static final double[] NE = NukeTourTest.NE;
    static final BlockType LIGHT_BLUE = new BlockType("minecraft:light_blue_terracotta", 1.25f, "pickaxe", true);
    static final BlockType YELLOW = new BlockType("minecraft:yellow_terracotta", 1.25f, "pickaxe", true);
    static final BlockType PACKED_ICE = new BlockType("minecraft:packed_ice", 0.5f, "pickaxe", false);
    /** The Baritone start-up of live j0024's walks to a target: asked, pathing 2 ticks later, the first step 1 later. */
    static final int BARITONE_START = 3;

    /**
     * 51 leftovers spread as j0024's: 47 walks of 5 to 116 ticks (most 5 to 21), so small groups a few blocks beyond
     * reach of each other, the groups across the tile.
     */
    static List<Pos> j0024Leftovers(long seed) {
        Random r = new Random(seed);
        Set<Pos> out = new LinkedHashSet<>();
        while (out.size() < 51) {
            int cx = 6 + r.nextInt(116);
            int cz = 6 + r.nextInt(116);
            int n = 2 + r.nextInt(6);
            int x = cx;
            int z = cz;
            for (int i = 0; i < n && out.size() < 51; i++) {
                double a = r.nextDouble() * 2 * Math.PI;
                double d = 4 + r.nextDouble() * 6;
                x = Math.max(1, Math.min(126, (int) Math.round(x + Math.cos(a) * d)));
                z = Math.max(1, Math.min(126, (int) Math.round(z + Math.sin(a) * d)));
                if (x == 63 && z == 63) continue;
                out.add(new Pos(x, Y, z));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * A j0024-like tile: 1,932 light blue terracotta, 1,631 cobbled deepslate, 219 packed ice and 24 yellow
     * terracotta in patches (discs of radius 2-6, about two thirds of their cells) over a 128×128 tile.
     */
    static Map<Pos, BlockType> j0024Like(long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        Object[][] kinds = {{LIGHT_BLUE, 1932}, {NukeMineMovingTest.COBBLED_DEEPSLATE, 1631}, {PACKED_ICE, 219},
            {YELLOW, 24}};
        for (Object[] k : kinds) {
            BlockType b = (BlockType) k[0];
            int want = (Integer) k[1];
            int have = 0;
            while (have < want) {
                int cx = r.nextInt(128);
                int cz = r.nextInt(128);
                int rad = 2 + r.nextInt(5);
                for (int x = cx - rad; x <= cx + rad && have < want; x++) {
                    for (int z = cz - rad; z <= cz + rad && have < want; z++) {
                        if (x < 0 || x > 127 || z < 0 || z > 127) continue;
                        if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > rad * rad || r.nextDouble() < 0.35) continue;
                        Pos p = new Pos(x, Y, z);
                        if (m.containsKey(p) || (x == 63 && z == 63)) continue;
                        m.put(p, b);
                        have++;
                    }
                }
            }
        }
        return m;
    }

    private static Predicate<Pos> floorBut(Set<Pos> targets) {
        return c -> c.x() >= 0 && c.x() < 128 && c.z() >= 0 && c.z() < 128 && !targets.contains(c);
    }

    // ------------------------------------------------------------------------------------------------ the plan

    /**
     * Every target is in exactly one cover (the feet's or a stand's), within {@link NukeLeftovers#COVER} of its stand's
     * centre; each stand is a cell the bot may stand in with at least one target; one waypoint per stand after the
     * feet's; no lane ends.
     */
    @Test
    void everyLeftoverIsCoveredOnceFromAStandInReach() {
        for (long seed = 1; seed <= 6; seed++) {
            List<Pos> ts = j0024Leftovers(seed);
            Set<Pos> set = new HashSet<>(ts);
            Predicate<Pos> standable = floorBut(set);
            NukeSweep.Plan pl = NukeLeftovers.plan(ts, 63.5, 63.5, Y, NE[0], NE[1], NukeLeftovers.COVER, standable);
            String what = "seed " + seed;
            assertTrue(pl.isTour(), what);
            List<Set<Pos>> covers = pl.covers();
            assertNotNull(covers);
            assertEquals(pl.lanes().size(), covers.size(), what);
            assertEquals(pl.waypoints().size(), pl.segmentOf().length, what);
            Set<Pos> seen = new HashSet<>();
            for (int k = 0; k < covers.size(); k++) {
                double[] w = pl.waypoints().get(k);
                if (k > 0) {
                    Pos s = new Pos((int) Math.floor(w[0]), Y, (int) Math.floor(w[1]));
                    assertTrue(standable.test(s), what + ": stand " + s);
                    assertFalse(covers.get(k).isEmpty(), what + ": an empty stand " + k);
                }
                for (Pos t : covers.get(k)) {
                    assertTrue(seen.add(t), what + ": covered twice " + t);
                    assertTrue(NukeLeftovers.reach(w[0], w[1], t) <= NukeLeftovers.COVER + 1e-9, what + ": " + t);
                }
            }
            assertEquals(set, seen, what + ": every leftover covered");
            assertTrue(pl.waypoints().size() - 1 < ts.size(), what + ": several from one stand somewhere");
        }
    }

    /** A group within reach of one cell is one stand; a target no allowed cell has in reach is in no cover. */
    @Test
    void aGroupIsOneStandAndAnUnreachableTargetNone() {
        List<Pos> group = List.of(new Pos(40, Y, 40), new Pos(42, Y, 41), new Pos(41, Y, 43), new Pos(39, Y, 42));
        Set<Pos> set = new HashSet<>(group);
        NukeSweep.Plan pl = NukeLeftovers.plan(group, 10.5, 10.5, Y, Double.NaN, Double.NaN, NukeLeftovers.COVER,
            floorBut(set));
        assertEquals(2, pl.waypoints().size(), "the feet and one stand");
        assertEquals(set, pl.covers().get(1));
        assertTrue(pl.covers().get(0).isEmpty());

        Pos lonely = new Pos(90, Y, 90);
        List<Pos> ts = new ArrayList<>(group);
        ts.add(lonely);
        Set<Pos> all = new HashSet<>(ts);
        Predicate<Pos> noneNear = c -> floorBut(all).test(c) && Math.abs(c.x() - 90) + Math.abs(c.z() - 90) > 8;
        NukeSweep.Plan p2 = NukeLeftovers.plan(ts, 10.5, 10.5, Y, Double.NaN, Double.NaN, NukeLeftovers.COVER, noneNear);
        for (Set<Pos> c : p2.covers()) assertFalse(c.contains(lonely), "no stand in reach of " + lonely);
        assertEquals(2, p2.waypoints().size());

        NukeSweep.Plan here = NukeLeftovers.plan(group, 40.5, 41.5, Y, Double.NaN, Double.NaN, NukeLeftovers.COVER,
            floorBut(set));
        assertEquals(1, here.waypoints().size(), "all in reach of the feet: no walk");
        assertEquals(set, here.covers().get(0));
    }

    /** With the end corner given the route ends nearer to it, and its walk there is no longer than without. */
    @Test
    void theRouteEndsTowardsTheEndCorner() {
        for (long seed = 1; seed <= 6; seed++) {
            List<Pos> ts = j0024Leftovers(seed);
            Predicate<Pos> st = floorBut(new HashSet<>(ts));
            NukeSweep.Plan toNe = NukeLeftovers.plan(ts, 63.5, 63.5, Y, NE[0], NE[1], NukeLeftovers.COVER, st);
            NukeSweep.Plan any = NukeLeftovers.plan(ts, 63.5, 63.5, Y, Double.NaN, Double.NaN, NukeLeftovers.COVER, st);
            double a = toNe.walk(63.5, 63.5, NE[0], NE[1]);
            double b = any.walk(63.5, 63.5, NE[0], NE[1]);
            System.out.printf(Locale.ROOT, "seed %d: %d stands, to NE %.0f blocks (with the way off), anywhere %.0f%n",
                seed, toNe.waypoints().size() - 1, a, b);
            assertTrue(a <= b + 1e-6, "seed " + seed + ": " + a + " vs " + b);
        }
    }

    // ------------------------------------------------------------------------------------------- the leftovers

    /** The run starting where the sweep left {@code real} still standing and {@code air} already air. */
    static NukeRunTest.Scenario leftovers(String name, List<Pos> real, List<Pos> air, boolean route) {
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        Random r = new Random(real.size() * 31L + air.size());
        for (Pos p : real) {
            double v = r.nextDouble();
            blocks.put(p, v < 0.44 ? NukeMineMovingTest.COBBLED_DEEPSLATE : v < 0.96 ? LIGHT_BLUE : PACKED_ICE);
        }
        NukeRunTest.Scenario sc = NukeTourTest.scenario(name, blocks, true);
        sc.airTargets = air;
        sc.leftoversNow = true;
        sc.leftoverRoute = route;
        sc.client = sc.client.andThen(c -> c.baritoneStartTicks = BARITONE_START);
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

    private static void print(NukeRunTest.Result r) {
        System.out.println(r.line());
        System.out.println("    " + r.sim().rules());
        System.out.printf(Locale.ROOT, "    leftovers %d (%d already air), %d ticks, %d Baritone walks, walk %.0f"
                + " blocks%n", r.run().leftovers(), r.run().leftoversAir(), r.run().leftoverTicks(),
            r.sim().baritoneWalks, r.sim().walked);
        for (String l : r.sim().log) if (l.startsWith("Fast nuke")) System.out.println("    " + l);
    }

    /** Route off and on over the same leftovers: {walks, route}. */
    private static NukeRunTest.Result[] both(String name, List<Pos> real, List<Pos> air) {
        NukeRunTest.Result before = leftovers(name + ", walks", real, air, false).run();
        NukeRunTest.Result after = leftovers(name + ", route", real, air, true).run();
        print(before);
        print(after);
        return new NukeRunTest.Result[]{before, after};
    }

    /** Average leftover ticks {walks, route} over the spreads, after one line per spread. */
    private static double[] report(String what, List<NukeRunTest.Result[]> rs) {
        double before = 0;
        double after = 0;
        for (NukeRunTest.Result[] r : rs) {
            before += r[0].run().leftoverTicks();
            after += r[1].run().leftoverTicks();
            System.out.printf(Locale.ROOT, "%s: walks %5d ticks (%3d Baritone walks, %4.0f blocks), route %5d ticks"
                    + " (%4.0f blocks)%n", r[0].name(), r[0].run().leftoverTicks(), r[0].sim().baritoneWalks,
                r[0].sim().walked, r[1].run().leftoverTicks(), r[1].sim().walked);
        }
        before /= rs.size();
        after /= rs.size();
        System.out.printf(Locale.ROOT, "%s, %d spreads: walks %.0f ticks on average, route %.0f%n", what, rs.size(),
            before, after);
        return new double[]{before, after};
    }

    /**
     * 51 leftovers spread as j0024's, none of them air yet, on 8 spreads: the route takes every spread in less time
     * than the Baritone walk to each, on average a fifth less at least, by the same rules and without a Baritone walk.
     * (Sim: about 1,050 ticks instead of 1,420; its walk across the tile, some 330-410 blocks at Soul Speed, is most of
     * it.)
     */
    @Test
    void fiftyOneLeftoversOnTheRoute() {
        List<NukeRunTest.Result[]> rs = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) rs.add(both("51 leftovers seed " + seed, j0024Leftovers(seed), List.of()));
        double[] avg = report("51 leftovers", rs);
        for (NukeRunTest.Result[] r : rs) {
            allBrokenByTheRules(r[0]);
            allBrokenByTheRules(r[1]);
            assertNull(r[0].run().leftoverRoute(), r[0].name());
            assertNotNull(r[1].run().leftoverRoute(), r[1].name());
            assertEquals(0, r[1].sim().baritoneWalks, r[1].name());
            assertTrue(r[1].run().leftoverTicks() < r[0].run().leftoverTicks(), r[1].name() + ": "
                + r[1].run().leftoverTicks() + " vs " + r[0].run().leftoverTicks());
        }
        assertTrue(avg[1] <= 0.8 * avg[0], avg[1] + " vs " + avg[0]);
    }

    /**
     * About half of the 51 already air when the sweep ends (the walks then take about as long as live j0024's): the
     * route counts those at once and walks to the rest only, the walks went to each; every spread faster, on average by
     * a quarter at least. (Sim: about 830 ticks instead of 1,220; live j0024: 1,173.)
     */
    @Test
    void leftoversAlreadyAirAreCountedNotWalkedTo() {
        List<NukeRunTest.Result[]> rs = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            List<Pos> ts = j0024Leftovers(seed);
            List<Pos> real = new ArrayList<>();
            List<Pos> air = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) (i % 2 == 0 ? real : air).add(ts.get(i));
            rs.add(both("26 + 25 air seed " + seed, real, air));
        }
        double[] avg = report("26 leftovers + 25 air", rs);
        for (NukeRunTest.Result[] r : rs) {
            allBrokenByTheRules(r[0]);
            allBrokenByTheRules(r[1]);
            assertEquals(25, r[1].run().leftoversAir(), r[1].name());
            assertTrue(r[1].run().leftoverTicks() < r[0].run().leftoverTicks(), r[1].name());
        }
        assertTrue(avg[1] <= 0.75 * avg[0], avg[1] + " vs " + avg[0]);
    }

    /**
     * Fewer than {@link NukeRun#ROUTE_MIN} left once those already air are counted: walked to one by one, as before
     * (no gain from a route).
     */
    @Test
    void aFewLeftoversAreDoneStanding() {
        List<Pos> ts = j0024Leftovers(3);
        int few = NukeRun.ROUTE_MIN - 1;
        NukeRunTest.Result r = leftovers(few + " leftovers + air", ts.subList(0, few), ts.subList(few, ts.size()), true)
            .run();
        print(r);
        allBrokenByTheRules(r);
        assertNull(r.run().leftoverRoute());
        assertEquals(ts.size() - few, r.run().leftoversAir());
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("too few for a route")), r.sim().log.toString());
    }

    // ---------------------------------------------------------------------------------------- j0024-like runs

    /** The keys: build.nukeLeftoverRoute, build.nukeInstantAckTicks, build.nukeHoldAtSpeed. */
    record Keys(boolean route, int ack, boolean hold) {
        static final Keys OFF = new Keys(false, 2, false);

        @Override
        public String toString() {
            return "route " + (route ? "on " : "off") + ", ack " + ack + ", hold " + (hold ? "on " : "off");
        }
    }

    /** A j0024-like tile on soul sand with 2b2t's late air and 0.3 % really refused, the keys as given. */
    static NukeRunTest.Scenario j0024(long seed, Keys k, double lateAir, int lateAirTicks) {
        NukeRunTest.Scenario sc = NukeTourTest.scenario("j0024-like seed " + seed + ", " + k, j0024Like(seed), true);
        sc.leftoverRoute = k.route();
        sc.instantAckTicks = k.ack();
        sc.holdAtSpeed = k.hold();
        sc.server = sc.server.andThen(s -> {
            s.lateAir = lateAir;
            s.lateAirTicks = lateAirTicks;
            s.refuseInstant = 0.003;
        });
        sc.client = sc.client.andThen(c -> c.baritoneStartTicks = BARITONE_START);
        return sc;
    }

    /**
     * Whole j0024-like rounds on 8 tiles, the air of 4 % of the blocks 1-6 or 1-10 ticks after the ack (j0024: about
     * 95 refusals of 3,711 with the 2-tick wait), each key alone and all three, every block broken by the rules (the
     * really refused ones too): the leftover route never makes a round longer and takes its leftovers in less time on
     * average; the 4-tick wait sends fewer STARTs to blocks the server has broken and counts fewer refusals; the hold at
     * speed leaves fewer leftovers.
     */
    @Test
    void j0024LikeRoundsWithLateAir() {
        Keys route = new Keys(true, 2, false);
        Keys ack = new Keys(false, 4, false);
        Keys hold = new Keys(false, 2, true);
        List<Keys> keys = List.of(Keys.OFF, route, ack, hold, new Keys(true, 4, true));
        Map<Keys, long[]> sums = new LinkedHashMap<>();
        int rounds = 0;
        for (int late : new int[]{6, 10}) {
            for (long seed = 1; seed <= 8; seed++) {
                Map<Keys, NukeRunTest.Result> rs = new LinkedHashMap<>();
                for (Keys k : keys) {
                    NukeRunTest.Result r = j0024(seed, k, 0.04, late).run();
                    allBrokenByTheRules(r);
                    rs.put(k, r);
                    System.out.printf(Locale.ROOT, "late %2d seed %d %s: %6d ticks, %3d refused, %3d STARTs on air, %3d"
                            + " AirLiquid, %2d left (%2d air), %4d ticks for them%n", late, seed, k, r.sim().ticks,
                        r.run().dig().refused, r.sim().airStarts, r.server().grim.airLiquidFlags, r.run().leftovers(),
                        r.run().leftoversAir(), r.run().leftoverTicks());
                    long[] s = sums.computeIfAbsent(k, x -> new long[5]);
                    s[0] += r.sim().ticks;
                    s[1] += r.run().dig().refused;
                    s[2] += r.sim().airStarts;
                    s[3] += r.run().leftovers();
                    s[4] += r.run().leftoverTicks();
                }
                rounds++;
                assertTrue(rs.get(route).sim().ticks <= rs.get(Keys.OFF).sim().ticks, "late " + late + " seed " + seed
                    + ": the route " + rs.get(route).sim().ticks + " vs " + rs.get(Keys.OFF).sim().ticks);
            }
        }
        for (var e : sums.entrySet()) {
            long[] s = e.getValue();
            System.out.printf(Locale.ROOT, "%s: %.0f ticks, %.1f refused, %.1f STARTs on air, %.1f left, %.0f ticks for"
                + " them (average of %d)%n", e.getKey(), s[0] / (double) rounds, s[1] / (double) rounds,
                s[2] / (double) rounds, s[3] / (double) rounds, s[4] / (double) rounds, rounds);
        }
        long[] off = sums.get(Keys.OFF);
        assertTrue(sums.get(route)[4] < off[4], "the route's leftover ticks");
        assertTrue(sums.get(route)[0] < off[0], "the route's rounds");
        assertTrue(sums.get(ack)[2] < off[2], "STARTs on broken blocks with the 4-tick wait");
        assertTrue(sums.get(ack)[1] < off[1], "refusals with the 4-tick wait");
        assertTrue(sums.get(hold)[3] < off[3], "leftovers with the hold at speed");
    }
}
