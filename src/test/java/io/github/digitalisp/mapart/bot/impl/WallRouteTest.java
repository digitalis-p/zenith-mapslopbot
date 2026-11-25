package io.github.digitalisp.mapart.bot.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The break-out route ({@link WallRoute}, {@code build.laneBreakOut}) on the art of the live blocks (2026-10-12, ellen
 * [0,6] on moss, fixtures/art/ellen_0_6.json; lanes are 9 columns wide, lane k covers columns 9k..9k+8, its walking line
 * column 9k+4). Tile cell (col, row) is (x, z) here; the walkway north of the tile (rows -3..-1) is open floor.
 * Placed pointed dripstone and pots are walls the pathfinder can neither walk through nor onto; carpets, plates and
 * roots are floor; terracotta and other full blocks are climbed.
 */
class WallRouteTest {
    static final Set<String> WALLS = Set.of("minecraft:pointed_dripstone", "minecraft:decorated_pot");
    static final Set<String> FULL = Set.of("terracotta", "minecraft:clay", "minecraft:crimson_nylium",
        "minecraft:warped_nylium", "minecraft:prismarine_bricks", "minecraft:raw_iron_block");
    static String[] art;

    @BeforeAll
    static void load() throws Exception {
        JsonObject o;
        try (InputStream in = WallRouteTest.class.getResourceAsStream("/fixtures/art/ellen_0_6.json")) {
            assertNotNull(in);
            o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> palette = new ArrayList<>();
        for (var e : o.getAsJsonArray("palette")) palette.add(e.getAsString().replaceAll("\\[.*", ""));
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(o.get("cells").getAsString()))
            .order(ByteOrder.LITTLE_ENDIAN);
        art = new String[128 * 128];
        for (int i = 0; i < art.length; i++) {
            int v = buf.getShort();
            art[i] = v < 0 ? null : palette.get(v);
        }
    }

    static String at(int col, int row) {
        return art[row * 128 + col];
    }

    /** The world with lanes 0..{@code lastLane} placed (columns 0..9·lastLane+8), {@code air} cells never placed. */
    static WallRoute.Grid world(int lastLane, Set<Pos> air) {
        Map<Pos, String> placed = new HashMap<>();
        for (int col = 0; col <= 9 * lastLane + 8; col++) {
            for (int row = 0; row < 128; row++) {
                Pos p = new Pos(col, 0, row);
                if (!air.contains(p) && at(col, row) != null) placed.put(p, at(col, row));
            }
        }
        return new WallRoute.Grid() {
            @Override
            public boolean passable(Pos c) {
                if (c.x() < 0 || c.x() > 127 || c.z() < -3 || c.z() > 127) return false;
                String b = placed.get(c);
                return b == null || !WALLS.contains(b); // floor, carpets, plates, roots; full blocks are climbed
            }

            @Override
            public boolean breakable(Pos c) {
                String b = placed.get(c);
                return b != null && WALLS.contains(b);
            }
        };
    }

    private static void assertDripstoneOnly(WallRoute.Route r) {
        for (Pos w : r.walls()) assertTrue(WALLS.contains(at(w.x(), w.z())), w + " in " + r);
    }

    @Test
    void lane8EndCagedLane9StartUnreachable() {
        // 10:09: lane 8's second pass ended at col 76 row 66 with dripstone on every side; lane 9 starts at col 85,
        // row 127 (its cells unplaced)
        Pos bot = new Pos(76, 0, 66);
        WallRoute.Grid g = world(8, Set.of(bot));
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertTrue(g.breakable(new Pos(76 + d[0], 0, 66 + d[1])), "caged: a wall on every side");
        }
        WallRoute.Route r = WallRoute.plan(g, bot, new Pos(85, 0, 127), 0, 24);
        assertNotNull(r);
        assertTrue(!r.walls().isEmpty() && r.walls().size() <= 2, r.toString());
        assertDripstoneOnly(r);
        assertEquals(new Pos(85, 0, 127), r.path().getLast());
    }

    @Test
    void repairLaneStartInsideTheField() {
        // 10:15: lane 8 again, its walk's start col 76 row 65 a placed dripstone inside the field; the bot comes from
        // the chests north of the tile; lanes 0..9 placed
        Pos bot = new Pos(76, 0, -2);
        Pos start = new Pos(76, 0, 65);
        WallRoute.Grid g = world(9, Set.of(new Pos(76, 0, 66)));
        WallRoute.Route r = WallRoute.plan(g, bot, start, 0, 24);
        assertNotNull(r);
        assertEquals(start, r.walls().getLast(), "the start cell itself holds a wall: broken last");
        assertTrue(r.walls().size() <= 3, r.toString());
        assertDripstoneOnly(r);
    }

    @Test
    void lane9RestartCellIsAPlacedDripstoneBesideTheBot() {
        // 10:18: lane 9's restart cell col 85 row 78, a placed dripstone on the walking line, the bot one block west
        Pos bot = new Pos(84, 0, 78);
        Pos start = new Pos(85, 0, 78);
        WallRoute.Route r = WallRoute.plan(world(9, Set.of()), bot, start, 0, 24);
        assertNotNull(r);
        assertEquals(List.of(start), r.walls(), "only the start cell");
        assertEquals(List.of(bot, start), r.path());
    }

    @Test
    void nothingToBreakWhenTheWayIsOpenAndNoWayThroughWhatMayNotBeBroken() {
        WallRoute.Grid open = world(-1, Set.of()); // nothing placed
        WallRoute.Route r = WallRoute.plan(open, new Pos(76, 0, -2), new Pos(85, 0, 127), 0, 24);
        assertNotNull(r);
        assertTrue(r.walls().isEmpty());
        // a ring the bot may not break: no route
        Set<Pos> ring = new HashSet<>();
        for (int d = -1; d <= 1; d++) {
            ring.add(new Pos(10 + d, 0, 9));
            ring.add(new Pos(10 + d, 0, 11));
            ring.add(new Pos(9, 0, 10 + d));
            ring.add(new Pos(11, 0, 10 + d));
        }
        WallRoute.Grid walled = new WallRoute.Grid() {
            @Override
            public boolean passable(Pos c) {
                return !ring.contains(c) && Math.abs(c.x()) < 60 && Math.abs(c.z()) < 60;
            }

            @Override
            public boolean breakable(Pos c) {
                return false;
            }
        };
        assertNull(WallRoute.plan(walled, new Pos(10, 0, 10), new Pos(30, 0, 30), 0, 24));
    }
}
