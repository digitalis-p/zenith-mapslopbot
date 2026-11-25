package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A carry-over's NUKE: the targets are the cells the next tile does
 * not keep, scattered among the kept blocks, which stay ({@code artCleared} false). Live 2026-11-03, Snowy Angel on
 * soul sand: [8,7] → [9,7] broke 2,433 blocks in 22 min (about 10.9 ticks a block; rounds of 7.3-15.3 and two of 86 and
 * 143), "Fast nuke: detour to … (a block that is no target in the way)" every 2-5 s, against about 4.0 for the normal
 * nuke; the owner watching: "a step every 5 seconds". The sweep's walking lines ran into a kept block every few cells. Now
 * the run walks the leftover route from the start ({@link NukeRun#routeFirst}), its stands also on top of the kept
 * blocks.
 */
class NukeCarryOverTest {
    static final int Y = NukeRunTest.Y;
    static final BlockType LAPIS = NukeLambdaTest.LAPIS;
    static final BlockType LIGHT_BLUE = NukeLeftoversTest.LIGHT_BLUE;
    static final BlockType CARPET = new BlockType("minecraft:white_carpet", 0.1f, null, false);

    /**
     * A {@code size}² field of lapis whose cells are targets with probability {@code share} (60 % light blue
     * terracotta, 40 % carpet by hand), as live on soul sand: Soul Speed, Lambda's scheme (8 instant STARTs a tick),
     * 2b2t latency, Baritone planning over the kept blocks' tops and starting 3 ticks late.
     */
    static NukeRunTest.Scenario carryOver(String name, int size, double share, long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> targets = new LinkedHashMap<>();
        Map<Pos, BlockType> kept = new LinkedHashMap<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                Pos p = new Pos(x, Y, z);
                if (x == 0 && z == 0) continue; // the bot's start
                if (r.nextDouble() < share) targets.put(p, r.nextDouble() < 0.6 ? LIGHT_BLUE : CARPET);
                else kept.put(p, LAPIS);
            }
        }
        NukeRunTest.Scenario sc = NukeMineMovingTest.soulSand(name, targets, false, 0.49);
        sc.others = kept;
        sc.artCleared = false;
        sc.startX = 0.5;
        sc.startZ = 0.5;
        sc.lambda = NukeLambdaTest.ON;
        sc.maxTicks = 400_000;
        sc.client = sc.client.andThen(c -> {
            c.lowIds = Set.of(CARPET.id());
            c.pitSight = true;
            c.pathing = true;
            c.elevation = true;
            c.baritoneStartTicks = 3;
        });
        return sc;
    }

    static double ticksPerBlock(NukeRunTest.Result r) {
        return (double) r.sim().ticks / Math.max(1, r.run().done());
    }

    private static void print(NukeRunTest.Result r) {
        System.out.println(r.line());
        System.out.printf(Locale.ROOT, "    %.2f ticks a block; %s%n", ticksPerBlock(r), r.run().summaryLine("done"));
        r.sim().log.stream().filter(l -> l.startsWith("Fast nuke") && !l.contains("detour to")
            && !l.contains("skipping")).forEach(l -> System.out.println("    " + l));
        System.out.println("    detours: " + r.sim().log.stream().filter(l -> l.contains("detour to")).count()
            + ", Baritone walks " + r.sim().baritoneWalks);
    }

    private static void allBrokenByTheRules(NukeRunTest.Result r) {
        assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
        assertEquals(r.targets().size(), r.run().done(), r.name() + ": " + r.run().summaryLine("done"));
        NukeLambdaTest.assertLambdaRules(r, 8);
        assertEquals(0, NukeVerifyTest.brokenOtherThanTargets(r), r.name() + ": a kept block was broken");
        assertEquals(0, r.run().watchdogTrips(), r.name());
    }

    /**
     * About 10 % of a lapis field scattered as targets (44 % of them carpets in pits), three spreads: the sweep before
     * ({@code build.nukeLeftoverRoute} off: as live, the route was off on kept art) against the route from the start.
     * The route breaks every target, no kept block, by the same rules, in at most 85 % of the sweep's ticks a block on
     * every spread. (Sim: the sweep 13.5, 12.9 and 11.5 ticks a block, two blocks of seed 8 given up by its watchdog as
     * live; the route 10.1, 10.1 and 8.5.)
     */
    @Test
    void scatteredTargetsAmongKeptLapisTakeTheRoute() {
        double before = 0;
        double after = 0;
        for (long seed = 7; seed <= 9; seed++) {
            NukeRunTest.Scenario sweep = carryOver("sweep, seed " + seed, 64, 0.10, seed);
            sweep.leftoverRoute = false;
            NukeRunTest.Result b = sweep.run();
            NukeRunTest.Result a = carryOver("route, seed " + seed, 64, 0.10, seed).run();
            print(b);
            print(a);
            allBrokenByTheRules(a);
            assertEquals(0, NukeVerifyTest.brokenOtherThanTargets(b), b.name() + ": a kept block was broken");
            assertFalse(b.run().routeFirst(), b.name());
            assertTrue(a.run().routeFirst(), a.name());
            assertTrue(a.sim().log.stream().anyMatch(l -> l.startsWith("Fast nuke: ") && l.contains("among kept blocks")),
                a.name());
            assertTrue(ticksPerBlock(a) <= ticksPerBlock(b) * 0.85, a.name() + ": " + ticksPerBlock(a) + " vs "
                + ticksPerBlock(b) + " ticks a block");
            before += ticksPerBlock(b);
            after += ticksPerBlock(a);
        }
        System.out.printf(Locale.ROOT, "ticks a block over 3 spreads: sweep %.2f, route %.2f%n", before / 3, after / 3);
    }

    /** A nuke whose art is cleared afterwards keeps the sweep: the route first is for kept art only. */
    @Test
    void aClearedLayerKeepsTheSweep() {
        NukeRunTest.Scenario sc = carryOver("cleared afterwards", 32, 0.10, 3);
        sc.artCleared = true;
        NukeRunTest.Result r = sc.run();
        assertFalse(r.run().routeFirst(), r.name());
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
    }

    /** Dense targets (a VERIFY-like layer mostly wrong): fewer kept blocks than {@link NukeRun#KEPT_ROUTE_SHARE}, the sweep. */
    @Test
    void denseTargetsKeepTheSweep() {
        NukeRunTest.Scenario sc = carryOver("dense", 32, 0.85, 4);
        sc.client = sc.client.andThen(c -> c.pitSight = false);
        NukeRunTest.Result r = sc.run();
        assertFalse(r.run().routeFirst(), r.name());
        allBrokenByTheRules(r);
    }
}
