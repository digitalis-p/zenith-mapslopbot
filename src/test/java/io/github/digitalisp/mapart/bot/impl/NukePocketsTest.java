package io.github.digitalisp.mapart.bot.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pockets on the fast nuke's floor ({@link NukePockets}, {@code build.nukePocketGuard}) on the art of the live incident
 * (2026-10-12, ellen [1,5] on moss, fixtures/nuke/ellen_1_5.json): 620 pointed dripstone among gray terracotta and
 * carpets. The bot broke a gray terracotta (col 66, row 48) whose four sides are dripstone and dropped into it, then was
 * caged on a carpet in a dripstone checkerboard (col 21, row 46); every walk out failed until the round was BLOCKED.
 * Tile cell (col, row) is (x, z) here, the art level at {@link NukeRunTest#Y}.
 */
class NukePocketsTest {
    static final int Y = NukeRunTest.Y;
    static final String DRIPSTONE = "minecraft:pointed_dripstone";
    static final Pos CAGE = new Pos(66, Y, 48);
    static final Pos CHECKERBOARD = new Pos(21, Y, 46);

    /** The art: every cell's block; the targets (the moss platform's nuke blocks); the others (dripstone, carpets ...). */
    static Map<Pos, BlockType> art;
    static Map<Pos, BlockType> targets;
    static Map<Pos, BlockType> others;
    static Set<Pos> dripstone;
    /** The art's pockets: closed groups of at most POCKET_MAX cells that are no dripstone. */
    static Set<Pos> pockets;
    static Set<String> lowIds;

    @BeforeAll
    static void load() throws Exception {
        Art a = load("/fixtures/nuke/ellen_1_5.json");
        art = a.art();
        targets = a.targets();
        others = a.others();
        dripstone = a.walls();
        pockets = a.pockets();
        lowIds = a.lowIds();
    }

    /**
     * A tile's art in the simulated world: every cell's block, the targets (the moss platform's nuke blocks), the
     * others, the walls among them (pointed dripstone, pots), the art's pockets (closed groups of at most POCKET_MAX
     * cells that are no wall) and the ids of the low others (carpets, plates, candles, lichen, roots).
     */
    record Art(Map<Pos, BlockType> art, Map<Pos, BlockType> targets, Map<Pos, BlockType> others, Set<Pos> walls,
               Set<Pos> pockets, Set<String> lowIds) { }

    static Art load(String resource) throws Exception {
        JsonObject o;
        try (InputStream in = NukePocketsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in);
            o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> palette = new java.util.ArrayList<>();
        for (var e : o.getAsJsonArray("palette")) palette.add(e.getAsString().replaceAll("\\[.*", ""));
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(o.get("cells").getAsString()))
            .order(ByteOrder.LITTLE_ENDIAN);
        Map<Pos, BlockType> art = new LinkedHashMap<>();
        Map<Pos, BlockType> targets = new LinkedHashMap<>();
        Map<Pos, BlockType> others = new LinkedHashMap<>();
        Set<Pos> walls = new HashSet<>();
        Set<String> lowIds = new HashSet<>();
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int i = buf.getShort();
                if (i < 0) continue;
                String id = palette.get(i);
                Pos p = new Pos(col, Y, row);
                BlockType b = type(id);
                art.put(p, b);
                if (nukeBlock(id)) {
                    targets.put(p, b);
                } else {
                    others.put(p, b);
                    if (WALL_IDS.contains(id)) walls.add(p);
                    else lowIds.add(id);
                }
            }
        }
        Set<Pos> pockets = new HashSet<>();
        Set<Pos> seen = new HashSet<>();
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                Pos s = new Pos(x, Y, z);
                if (walls.contains(s) || !seen.add(s)) continue;
                Set<Pos> group = new HashSet<>(Set.of(s));
                ArrayDeque<Pos> q = new ArrayDeque<>(group);
                while (!q.isEmpty()) {
                    Pos p = q.poll();
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        Pos n = new Pos(p.x() + d[0], Y, p.z() + d[1]);
                        if (n.x() < 0 || n.x() > 127 || n.z() < 0 || n.z() > 127 || walls.contains(n)) continue;
                        if (seen.add(n)) {
                            group.add(n);
                            q.add(n);
                        }
                    }
                }
                if (group.size() <= NukePockets.POCKET_MAX) pockets.addAll(group);
            }
        }
        return new Art(art, targets, others, walls, pockets, lowIds);
    }

    static final Set<String> WALL_IDS = Set.of(DRIPSTONE, "minecraft:decorated_pot");

    /** The moss platform's nuke blocks among the arts' (live config). */
    private static boolean nukeBlock(String id) {
        return id.endsWith("terracotta") || Set.of("minecraft:clay", "minecraft:prismarine_bricks",
            "minecraft:warped_nylium", "minecraft:crimson_nylium", "minecraft:raw_iron_block", "minecraft:redstone_block",
            "minecraft:grass_block", "minecraft:packed_ice").contains(id);
    }

    private static BlockType type(String id) {
        if (id.endsWith("terracotta")) return new BlockType(id, 1.25f, "pickaxe", true);
        return switch (id) {
            case "minecraft:clay" -> DigServer.CLAY;
            case "minecraft:prismarine_bricks" -> new BlockType(id, 1.5f, "pickaxe", true);
            case "minecraft:warped_nylium", "minecraft:crimson_nylium" -> new BlockType(id, 0.4f, "pickaxe", true);
            case "minecraft:raw_iron_block", "minecraft:redstone_block" -> new BlockType(id, 5f, "pickaxe", true);
            case "minecraft:grass_block" -> new BlockType(id, 0.6f, "shovel", false);
            case "minecraft:packed_ice" -> new BlockType(id, 0.5f, "pickaxe", false);
            case DRIPSTONE -> new BlockType(id, 1.5f, "pickaxe", true);
            case "minecraft:decorated_pot" -> new BlockType(id, 0f, null, false);
            default -> new BlockType(id, 0.1f, null, false); // carpets, plates, candles, lichen, roots
        };
    }

    /** The art's floor as the run sees it before anything is broken: every cell but dripstone is passable. */
    private static NukePockets floor() {
        return new NukePockets(new NukePockets.Floor() {
            @Override
            public boolean passable(Pos c) {
                return c.x() >= 0 && c.x() < 128 && c.z() >= 0 && c.z() < 128 && !dripstone.contains(c);
            }

            @Override
            public boolean breakable(Pos c) {
                return dripstone.contains(c);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- the grid

    @Test
    void theArtHasSmallPocketsAndOneBigFloor() {
        // 72 closed groups of 1-4 cells (18 hold a target), one open floor of 15,676 cells
        assertTrue(pockets.contains(CAGE));
        assertTrue(pockets.contains(CHECKERBOARD));
        NukePockets f = floor();
        assertTrue(f.pocket(CAGE));
        assertTrue(f.pocket(CHECKERBOARD));
        assertFalse(f.pocket(new Pos(10, Y, 10)));
        assertFalse(f.pocket(new Pos(66, Y, 47)), "a wall is in no pocket");
        assertTrue(f.samePocket(CAGE, CAGE));
        assertFalse(f.samePocket(CAGE, CHECKERBOARD));
    }

    @Test
    void theWayOutBreaksOneDripstone() {
        for (Pos from : List.of(CAGE, CHECKERBOARD)) {
            NukePockets.Route r = floor().escape(from);
            assertNotNull(r, from.toString());
            assertEquals(1, r.walls().size(), from + ": " + r);
            assertTrue(dripstone.containsAll(r.walls()));
            assertFalse(pockets.contains(r.exit()), r.toString());
            assertEquals(from, r.path().getFirst());
            assertEquals(r.exit(), r.path().getLast());
        }
        assertNull(floor().escape(new Pos(10, Y, 10)), "in no pocket: nothing to break");
    }

    @Test
    void everyPocketOfTheArtHasOneShortRouteOut() {
        // one route per pocket, through the fewest dripstone (never one break per pocket on the way)
        int most = 0;
        for (Pos from : pockets) {
            NukePockets.Route r = floor().escape(from);
            assertNotNull(r, from.toString());
            assertFalse(pockets.contains(r.exit()));
            assertTrue(dripstone.containsAll(r.walls()), r.toString());
            for (int i = 1; i < r.path().size(); i++) {
                Pos a = r.path().get(i - 1);
                Pos b = r.path().get(i);
                assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), r.toString());
            }
            most = Math.max(most, r.walls().size());
        }
        assertEquals(2, most, "most dripstone on one way out");
    }

    // ------------------------------------------------------------------------------------------- simulated runs

    /** The art in the simulated world: targets, the others in the way; carpets low; Baritone plans around dripstone. */
    private static NukeRunTest.Scenario scenario(String name, boolean guard, Pos start, boolean onTop) {
        return scenario(name, guard, start, onTop, p -> true);
    }

    /** {@link #scenario} with only the art's cells {@code window} holds (the rest of the tile is open floor). */
    private static NukeRunTest.Scenario scenario(String name, boolean guard, Pos start, boolean onTop,
                                                 java.util.function.Predicate<Pos> window) {
        NukeRunTest.Scenario sc = new NukeRunTest.Scenario(name);
        sc.blocks = new LinkedHashMap<>(targets);
        sc.others = new LinkedHashMap<>(others);
        sc.blocks.keySet().removeIf(window.negate());
        sc.others.keySet().removeIf(window.negate());
        sc.pocketGuard = guard;
        sc.startX = start.x() + 0.5;
        sc.startZ = start.z() + 0.5;
        sc.startOnTop = onTop;
        sc.maxTicks = 60_000;
        sc.client = c -> {
            c.lowIds = lowIds;
            c.wallIds = Set.of(DRIPSTONE);
            c.clearableIds = Set.of(DRIPSTONE, "minecraft:decorated_pot");
            c.pathing = true;
            c.elevation = true;
        };
        return sc;
    }

    private static long dripstoneBroken(NukeRunTest.Result r) {
        return r.server().destroys.stream().filter(d -> d.block().id().equals(DRIPSTONE)).count();
    }

    private static void print(NukeRunTest.Result r) {
        System.out.println(r.line());
        for (String l : r.sim().log) {
            if (l.startsWith("Break round") || l.startsWith("Fast nuke")) System.out.println("    " + l);
        }
    }

    @Test
    void caughtInTheCageWithoutTheGuardTheRoundStallsAsLive() {
        NukeRunTest.Scenario sc = scenario("cage, guard off", false, CAGE, false);
        NukeRunTest.Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains("no progress"), r.run().failure());
        assertEquals(0, dripstoneBroken(r));
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("failed (No path found")), r.sim().log.toString());
    }

    @Test
    void caughtInTheCageTheGuardBreaksOneDripstoneAndFinishes() {
        for (Pos start : List.of(CAGE, CHECKERBOARD)) {
            NukeRunTest.Scenario sc = scenario("caged at " + start + ", guard on", true, start, false);
            NukeRunTest.Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertEquals(1, r.run().escapes());
            assertEquals(1, dripstoneBroken(r), "only the dripstone in the way");
            assertEquals(0, r.run().watchdogTrips(), r.run().summaryLine("x"));
            List<String> lines = r.sim().log.stream().filter(l -> l.contains("walled in at " + start)).toList();
            assertEquals(1, lines.size(), r.sim().log.toString());
            assertTrue(lines.getFirst().contains("broke pointed_dripstone at "), lines.getFirst());
            r.assertRules(sc.perTick);
        }
    }

    @Test
    void onTopOfTheCagedTerracottaTheSweepDropsInWithoutTheGuardNotWithIt() {
        // as live: Baritone left the bot on top of the gray terracotta of the cage, mid-sweep (here: one lane along the
        // cage's column, x 62-70, starting at the cage); the sweep breaks the blocks under the bot first
        java.util.function.Predicate<Pos> lane = p -> p.x() >= 62 && p.x() <= 70 && p.z() >= 46 && p.z() <= 80;
        for (boolean guard : new boolean[]{false, true}) {
            NukeRunTest.Scenario sc = scenario("on the cage, one lane, guard " + (guard ? "on" : "off"), guard, CAGE,
                true, lane);
            sc.axis = NukeSweep.Axis.Z;
            AtomicReference<Pos> caged = new AtomicReference<>();
            NukeRunTest.Result r = run(sc, caged, Set.of(CAGE));
            print(r);
            if (!guard) {
                assertEquals(CAGE, caged.get(), "dropped into the cage");
                assertEquals(TaskStatus.FAILED, r.status());
                // walled in: every walk out fails (Baritone: "No path found"), the round ends without the rest
                assertTrue(r.run().failure().contains("No path found"), r.run().failure());
                continue;
            }
            assertNull(caged.get(), "dropped into the cage");
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertEquals(0, dripstoneBroken(r));
            assertEquals(0, r.run().watchdogTrips(), r.run().summaryLine("x"));
            r.assertRules(sc.perTick);
        }
    }

    @Test
    void theGuardNeverLetsAPocketFormOnThatArt() {
        // the whole art from the tile's corner: never on the floor of a pocket, no dripstone broken, no watchdog
        NukeRunTest.Scenario sc = scenario("whole art, guard on", true, new Pos(0, Y, 0), false);
        AtomicReference<Pos> caged = new AtomicReference<>();
        NukeRunTest.Result r = run(sc, caged, pockets);
        print(r);
        assertNull(caged.get(), "stood in a pocket at " + caged.get());
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertEquals(0, r.run().escapes());
        assertEquals(0, dripstoneBroken(r));
        assertEquals(0, r.run().watchdogTrips(), r.run().summaryLine("x"));
        r.assertRules(sc.perTick);
    }

    /** Runs {@code sc}, noting the first of {@code watch} (pocket cells) the bot stood in on the floor. */
    private static NukeRunTest.Result run(NukeRunTest.Scenario sc, AtomicReference<Pos> caged, Set<Pos> watch) {
        var client = sc.client;
        sc.client = c -> {
            client.accept(c);
            c.afterTick = () -> {
                if (c.elevated || caged.get() != null) return;
                Pos at = new Pos((int) Math.floor(c.feetX()), Y, (int) Math.floor(c.feetZ()));
                if (watch.contains(at)) caged.set(at);
            };
        };
        return sc.run();
    }

    @Test
    void routesPreferFewerBreaksOverShorterWalks() {
        // a 1 × 5 pocket: two walls thick next to the bot (west), one wall 5 cells away (east), the long sides protected
        Set<Pos> walls = new HashSet<>(Set.of(new Pos(-1, 0, 0), new Pos(-2, 0, 0), new Pos(5, 0, 0)));
        Set<Pos> unbreakable = new HashSet<>();
        for (int x = -3; x <= 6; x++) {
            unbreakable.add(new Pos(x, 0, -1));
            unbreakable.add(new Pos(x, 0, 1));
        }
        walls.addAll(unbreakable);
        NukePockets f = new NukePockets(new NukePockets.Floor() {
            @Override
            public boolean passable(Pos c) {
                return Math.abs(c.x()) <= 30 && Math.abs(c.z()) <= 30 && !walls.contains(c);
            }

            @Override
            public boolean breakable(Pos c) {
                return walls.contains(c) && !unbreakable.contains(c);
            }
        });
        NukePockets.Route r = f.escape(new Pos(0, 0, 0));
        assertNotNull(r);
        assertEquals(1, r.walls().size(), r.toString());
        assertEquals(new Pos(5, 0, 0), r.walls().getFirst());
    }
}
