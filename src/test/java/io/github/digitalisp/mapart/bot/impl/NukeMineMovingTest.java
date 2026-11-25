package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mining on the move ({@code build.nukeMineMoving}) and the sweep's end near the way off
 * ({@code site.platforms[].nukeEndCorner}) in whole runs ({@link NukeSim}) under the live soul sand conditions of
 * 2026-10-23 (j0015): the NUKE breaks only the art's cobbled deepslate, scattered and in patches (110-839 blocks a map),
 * every one mined (3 ticks with the netherite pickaxe, Efficiency V, Haste II); one START per tick; Soul Speed on soul
 * sand (top speed 0.49 blocks a tick, the keys accelerating the bot and the slide after them); the keys chosen for the
 * look's yaw; the look checked from where the server knows the bot; 2b2t latency. The same tile with and without:
 * every rule of {@link NukeRunTest.Result#assertRules} (no STOP out of reach, Grim FarBreak among them), every block
 * broken, nothing aborted, and the time.
 */
class NukeMineMovingTest {
    static final int Y = NukeRunTest.Y;
    static final BlockType COBBLED_DEEPSLATE = new BlockType("minecraft:cobbled_deepslate", 3.5f, "pickaxe", true);

    /**
     * A tile's cobbled deepslate: {@code patches} patches (discs of radius 1-4, half their cells) and {@code singles}
     * scattered blocks.
     */
    static Map<Pos, BlockType> cobbled(long seed, int patches, int singles) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int i = 0; i < patches; i++) {
            int cx = r.nextInt(128);
            int cz = r.nextInt(128);
            int rad = 1 + r.nextInt(4);
            for (int x = cx - rad; x <= cx + rad; x++) {
                for (int z = cz - rad; z <= cz + rad; z++) {
                    if (x < 0 || x > 127 || z < 0 || z > 127) continue;
                    if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > rad * rad || r.nextDouble() < 0.5) continue;
                    m.put(new Pos(x, Y, z), COBBLED_DEEPSLATE);
                }
            }
        }
        for (int i = 0; i < singles; i++) m.put(new Pos(r.nextInt(128), Y, r.nextInt(128)), COBBLED_DEEPSLATE);
        m.remove(new Pos(63, Y, 63)); // the map station, where the NUKE starts
        return m;
    }

    /** Live j0015 on soul sand: the bot starts at the map station (the tile's centre). */
    static NukeRunTest.Scenario soulSand(String name, Map<Pos, BlockType> blocks, boolean mineMoving, double top) {
        NukeRunTest.Scenario sc = new NukeRunTest.Scenario(name);
        sc.perTick = 1;
        sc.blocks = blocks;
        sc.mineMoving = mineMoving;
        sc.startX = 63.5;
        sc.startZ = 63.5;
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.jitter = 1;
        };
        sc.client = c -> {
            c.slide = true;
            c.topSpeed = top;
            c.geometricLook = true;
            c.keysFollowLook = true;
        };
        return sc;
    }

    private static void print(NukeRunTest.Result r) {
        System.out.println(r.line());
        System.out.println("    " + r.sim().rules());
        System.out.println("    " + r.run().summaryLine("done"));
    }

    private static void allBrokenByTheRules(NukeRunTest.Result r) {
        String line = r.run().summaryLine("done");
        assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
        assertEquals(r.targets().size(), r.run().done(), r.name() + ": " + line);
        r.assertRules(1);
        assertEquals(0, r.run().dig().aborted, r.name() + ": " + line);
        assertEquals(0, r.run().watchdogTrips(), r.name() + ": " + line);
    }

    /**
     * The tile sizes of j0015 (light, average and heavy maps): mining on the move takes at least a tenth off the round
     * on each (the walk no longer stands for every block), by the same rules.
     */
    @Test
    void soulSandTilesAreBrokenFasterOnTheMove() {
        int[][] tiles = {{8, 60}, {20, 120}, {40, 200}};
        List<String> table = new ArrayList<>();
        for (int i = 0; i < tiles.length; i++) {
            Map<Pos, BlockType> blocks = cobbled(31 + i, tiles[i][0], tiles[i][1]);
            NukeRunTest.Result before = soulSand("soul speed, " + blocks.size() + " cobbled, standing", blocks, false,
                0.49).run();
            NukeRunTest.Result after = soulSand("soul speed, " + blocks.size() + " cobbled, on the move", blocks, true,
                0.49).run();
            print(before);
            print(after);
            allBrokenByTheRules(before);
            allBrokenByTheRules(after);
            assertTrue(after.run().summaryLine("x").contains("started walking"), after.run().summaryLine("x"));
            assertTrue(after.sim().ticks < before.sim().ticks * 0.9, after.line() + " vs " + before.line());
            table.add(String.format(Locale.ROOT, "%4d blocks: %6d -> %6d ticks (%.0f -> %.0f s, %.0f %% less)",
                blocks.size(), before.sim().ticks, after.sim().ticks, before.sim().ticks / 20.0,
                after.sim().ticks / 20.0, 100.0 * (before.sim().ticks - after.sim().ticks) / before.sim().ticks));
        }
        table.forEach(System.out::println);
    }

    /** Walking speed (no Soul Speed boots: j0015 [1,0] and [2,0]): the same rules, and faster too. */
    @Test
    void withoutSoulSpeedItIsFasterToo() {
        Map<Pos, BlockType> blocks = cobbled(41, 20, 120);
        NukeRunTest.Result before = soulSand("walking, standing", blocks, false, NukeSim.WALK).run();
        NukeRunTest.Result after = soulSand("walking, on the move", blocks, true, NukeSim.WALK).run();
        print(before);
        print(after);
        allBrokenByTheRules(before);
        allBrokenByTheRules(after);
        assertTrue(after.sim().ticks < before.sim().ticks * 0.9, after.line() + " vs " + before.line());
    }

    /**
     * A j0014-like mix (terracotta and nylium instant, raw iron and cobbled deepslate mined, Soul Speed): every rule
     * kept and nothing left, the instants still broken on the way.
     */
    @Test
    void aMixOfInstantAndMinedBlocksKeepsTheRules() {
        Random r = new Random(5);
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                double v = r.nextDouble();
                if (v < 0.25) blocks.put(new Pos(x, Y, z), NukeRunTest.GRAY_TERRACOTTA);
                else if (v < 0.30) blocks.put(new Pos(x, Y, z), NukeRunTest.RAW_IRON);
                else if (v < 0.36) blocks.put(new Pos(x, Y, z), COBBLED_DEEPSLATE);
            }
        }
        blocks.remove(new Pos(0, Y, 0));
        NukeRunTest.Scenario sc = soulSand("mixed, on the move", blocks, true, 0.49);
        sc.startX = 0.5;
        sc.startZ = 0.5;
        NukeRunTest.Result after = sc.run();
        NukeRunTest.Scenario sb = soulSand("mixed, standing", blocks, false, 0.49);
        sb.startX = 0.5;
        sb.startZ = 0.5;
        NukeRunTest.Result before = sb.run();
        print(before);
        print(after);
        allBrokenByTheRules(before);
        allBrokenByTheRules(after);
        assertTrue(after.sim().ticks <= before.sim().ticks * 1.02, after.line() + " vs " + before.line());
    }

    /**
     * The live 2026-10-18 east edge ({@link NukeEdgeGuardTest}) with cobbled deepslate mined on the move at Soul Speed:
     * the edge guard still keeps the box out of the zone over the trench.
     */
    @Test
    void theEdgeGuardStillKeepsTheBoxOutOfTheZone() {
        for (double yaw : new double[]{8, -8, 20}) {
            NukeRunTest.Scenario s = NukeEdgeGuardTest.eastEdge("edge, mined on the move, yaw " + yaw, false, yaw);
            Map<Pos, BlockType> art = new LinkedHashMap<>();
            Random r = new Random(7);
            for (Pos p : s.blocks.keySet()) {
                if (r.nextDouble() < 0.08) art.put(p, COBBLED_DEEPSLATE);
            }
            s.blocks = art;
            s.mineMoving = true;
            var edge = s.client;
            s.client = c -> {
                edge.accept(c);
                c.slide = true;
                c.topSpeed = 0.49;
                c.geometricLook = true;
            };
            NukeRunTest.Result res = s.run();
            print(res);
            assertEquals(TaskStatus.SUCCESS, res.status(), res.name() + ": " + res.sim().log);
            assertEquals(res.targets().size(), res.run().done(), res.name());
            assertEquals(0, res.sim().hazardTicks, res.name() + ": in the zone at " + res.sim().firstHazard);
            res.assertRules(1);
        }
    }

    /** Lane-end pauses for mends still stop on a lane end, nothing in flight. */
    @Test
    void lanEndPausesStillStopOnALaneEnd() {
        Map<Pos, BlockType> blocks = cobbled(51, 30, 150);
        NukeRunTest.Scenario sc = soulSand("pauses, on the move", blocks, true, 0.49);
        int[] asked = {0};
        sc.pause = sim -> () -> ++asked[0] == 3 ? "netherite_pickaxe due for mending" : null;
        NukeRunTest.Result r = sc.run();
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains(io.github.digitalisp.mapart.bot.BotActions.BREAK_PAUSED), r.run().failure());
        NukeSweep.Plan plan = r.run().plan();
        assertNotNull(plan);
        double fx = r.sim().x;
        double fz = r.sim().z;
        assertTrue(plan.laneEnds().stream().anyMatch(i -> Math.hypot(plan.waypoints().get(i)[0] - fx,
            plan.waypoints().get(i)[1] - fz) < 0.6), "paused at a lane end: " + fx + ", " + fz);
        assertTrue(r.run().canPark(), "nothing in flight at the pause");
        r.assertRules(1);
    }

    // ------------------------------------------------------------------------------------------- sweep end

    /**
     * A full tile (every lane spans it): the sweep ends at the corner asked for, from the centre and from a corner. A
     * sparse one (lanes span their own targets): the walk to the start plus from the end to the corner is never longer
     * than without the end point, and the lanes are the same.
     */
    @Test
    void theSweepEndsNearTheCornerAskedFor() {
        Map<Pos, BlockType> full = NukeRunTest.ellenLike(3);
        Map<Pos, BlockType> sparse = cobbled(61, 40, 200);
        for (String name : new String[]{"NW", "NE", "SW", "SE"}) {
            double[] c = NukeSweep.corner(name, 0, 0, 128);
            assertNotNull(c, name);
            for (double[] from : new double[][]{{63.5, 63.5}, {0.5, 0.5}, {127.5, 0.5}}) {
                for (NukeSweep.Axis axis : NukeSweep.Axis.values()) {
                    String what = name + " " + axis + " from " + from[0] + "," + from[1];
                    NukeSweep.Plan plan = NukeSweep.plan(full.keySet(), axis, 4, 2, from[0], from[1], c[0], c[1]);
                    double[] end = plan.waypoints().getLast();
                    // from the map station every start is about as far: the end decides
                    if (from[0] == 63.5) {
                        assertTrue(Math.hypot(end[0] - c[0], end[1] - c[1]) < 6, what + ": ends at " + end[0] + ","
                            + end[1]);
                    }
                    NukeSweep.Plan fullAny = NukeSweep.plan(full.keySet(), axis, 4, 2, from[0], from[1]);
                    assertTrue(walkOutside(plan, from, c) <= walkOutside(fullAny, from, c) + 1e-9, what);
                    NukeSweep.Plan to = NukeSweep.plan(sparse.keySet(), axis, 4, 2, from[0], from[1], c[0], c[1]);
                    NukeSweep.Plan any = NukeSweep.plan(sparse.keySet(), axis, 4, 2, from[0], from[1]);
                    assertTrue(walkOutside(to, from, c) <= walkOutside(any, from, c) + 1e-9, what);
                    assertEquals(any.lanes().size(), to.lanes().size(), what);
                    // the lanes' own length is the same; the joins between them may differ by a few blocks
                    assertEquals(any.length(), to.length(), 60, what);
                }
            }
        }
        assertNull(NukeSweep.corner("", 0, 0, 128));
        assertNull(NukeSweep.corner("north", 0, 0, 128));
        assertNull(NukeSweep.corner(null, 0, 0, 128));
        double[] ne = NukeSweep.corner(" ne ", -64, -64, 128);
        assertNotNull(ne);
        assertEquals(64.0, ne[0]);
        assertEquals(-64.0, ne[1]);
    }

    /** From the feet to the plan's start plus from its end to {@code to} (blocks, straight). */
    private static double walkOutside(NukeSweep.Plan plan, double[] from, double[] to) {
        double[] a = plan.waypoints().getFirst();
        double[] b = plan.waypoints().getLast();
        return Math.hypot(a[0] - from[0], a[1] - from[1]) + Math.hypot(b[0] - to[0], b[1] - to[1]);
    }

    /** Without an end point the plan is the one before (start at the lane end nearest to the bot). */
    @Test
    void withoutAnEndPointThePlanIsUnchanged() {
        Map<Pos, BlockType> tile = cobbled(62, 40, 200);
        for (NukeSweep.Axis axis : NukeSweep.Axis.values()) {
            NukeSweep.Plan a = NukeSweep.plan(tile.keySet(), axis, 4, 2, 130, 130);
            NukeSweep.Plan b = NukeSweep.plan(tile.keySet(), axis, 4, 2, 130, 130, Double.NaN, Double.NaN);
            assertEquals(a.lanes(), b.lanes());
            assertEquals(a.waypoints().size(), b.waypoints().size());
            for (int i = 0; i < a.waypoints().size(); i++) {
                assertEquals(a.waypoints().get(i)[0], b.waypoints().get(i)[0]);
                assertEquals(a.waypoints().get(i)[1], b.waypoints().get(i)[1]);
            }
        }
    }

    /** The corner comes from the platform holding the targets; none for another platform's or without the key. */
    @Test
    void theEndCornerIsThePlatformsOwn() {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg soul = new MapArtConfig.PlatformCfg();
        soul.id = "soulsand";
        soul.minX = 128;
        soul.minZ = -128;
        soul.artY = Y;
        soul.nukeEndCorner = "NE";
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.minX = 0;
        moss.minZ = 0;
        moss.artY = Y;
        site.platforms = List.of(moss, soul);
        double[] e = BreakBlocksTask.nukeEnd(site, List.of(new Pos(140, Y, -100)));
        assertEquals(256.0, e[0]);
        assertEquals(-128.0, e[1]);
        assertTrue(Double.isNaN(BreakBlocksTask.nukeEnd(site, List.of(new Pos(10, Y, 10)))[0]), "moss: none set");
        assertTrue(Double.isNaN(BreakBlocksTask.nukeEnd(site, List.of(new Pos(140, Y + 1, -100)))[0]), "not the art");
        assertTrue(Double.isNaN(BreakBlocksTask.nukeEnd(site, List.of())[0]));
    }

    /**
     * The whole NUKE's walk: from the map station through the sweep to the way off at the north-east corner. Ending
     * there makes the walk after the sweep short, and the whole shorter.
     */
    @Test
    void endingNearTheWayOffShortensTheWalkAfterTheSweep() {
        Map<Pos, BlockType> blocks = cobbled(71, 20, 120);
        double[] ne = NukeSweep.corner("NE", 0, 0, 128);
        assertNotNull(ne);
        NukeRunTest.Scenario plain = soulSand("end anywhere", blocks, true, 0.49);
        NukeRunTest.Scenario toNe = soulSand("end near NE", blocks, true, 0.49);
        toNe.endX = ne[0];
        toNe.endZ = ne[1];
        NukeRunTest.Result a = plain.run();
        NukeRunTest.Result b = toNe.run();
        print(a);
        print(b);
        allBrokenByTheRules(a);
        allBrokenByTheRules(b);
        double offA = Math.hypot(a.sim().x - ne[0], a.sim().z - ne[1]);
        double offB = Math.hypot(b.sim().x - ne[0], b.sim().z - ne[1]);
        System.out.printf(Locale.ROOT, "sweep end to NE: %.0f -> %.0f blocks; ticks %d + %.0f -> %d + %.0f%n", offA,
            offB, a.sim().ticks, offA / 0.49, b.sim().ticks, offB / 0.49);
        // this tile's east lane is short (its blocks lie in the middle): no end is near the corner, the nearest one is
        assertTrue(offB <= offA + 1e-9, "ends nearer NE: " + offB + " vs " + offA);
        assertTrue(b.sim().ticks + offB / 0.49 <= a.sim().ticks + offA / 0.49 + 1, "the whole walk no longer");
    }
}
