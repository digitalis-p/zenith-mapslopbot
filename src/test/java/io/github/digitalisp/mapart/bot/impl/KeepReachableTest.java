package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keep reachable ({@code build.laneKeepReachable}, LaneRun class javadoc "Keep reachable") on the live incident's art
 * (ellen [1,6] on moss, fixtures/art/ellen_1_6.json; 2026-10-13). Lane 9 (columns 81-89, walking line 85) crosses a
 * pointed-dripstone field (columns 36-95, rows 39-61). Its second pass started at row 62 northwards at 00:40:17, was
 * "514 cells stuck at" column 85.6, row 58.0 and left them to stand spots. From the replay of that run: at 00:42:07,
 * from a stand spot at column 83.45, row 50.59, the bot placed the dripstone at (82,54) and (81,53), which closed the
 * 2-cell area {(81,54), (81,55)}; at 00:42:17 it placed (81,54) from column 81.55, row 50.48. (81,55) had never been in
 * reach of a stand spot it used (no click at it in the whole recording) and was left as a hole no stand spot could
 * reach: VERIFY skipped it on 4 passes and BLOCKED at 01:03. On the simulated platform (Baritone neither walks through
 * nor onto dripstone and pots).
 */
class KeepReachableTest {
    static final Set<String> WALLS = LaneWayOutTest.WALLS;
    static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    static TileRunner liveState(TileArt art) {
        TileRunner r = LaneReentryTest.runner(5, true);
        r.finiteStock = false;
        r.sim.wallIds = WALLS;
        for (int col = 0; col < TileArt.SIZE; col++) {
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec b = art.get(col, row);
                if (b == null) continue;
                boolean placed = col <= 80 || (col <= 89 && (row >= 62 || (row == 61 && col >= 84 && col <= 86)));
                if (placed) r.world.put(cell(r, col, row), b);
            }
        }
        return r;
    }

    static Pos cell(TileRunner r, int col, int row) {
        return new Pos(r.geo.minX() + col, r.geo.artY(), r.geo.minZ() + row);
    }

    /** Lane 9's cells still to place, walked northwards from row 62 (live: from its first pass's end). */
    static Lane lane9(TileRunner r, TileArt art) {
        Lane l = LaneReentryTest.lane(r, art, 9);
        List<Placement> ps = new ArrayList<>(l.placements());
        ps.sort((a, b) -> Integer.compare(b.pos().z(), a.pos().z()));
        return new Lane(9, cell(r, 85, 62), cell(r, 85, -1), List.copyOf(ps));
    }

    /** Unplaced art cells no walk from the tile's south edge reaches (walls: placed dripstone and pots). */
    static List<String> enclosed(TileRunner r, TileArt art) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int col = 0; col < TileArt.SIZE; col++) {
            if (!wall(r, col, 127)) {
                seen.add(key(col, 127));
                q.add(new int[]{col, 127});
            }
        }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + d[0];
                int z = c[1] + d[1];
                if (x < 0 || z < 0 || x >= TileArt.SIZE || z >= TileArt.SIZE || seen.contains(key(x, z)) || wall(r, x, z)) {
                    continue;
                }
                seen.add(key(x, z));
                q.add(new int[]{x, z});
            }
        }
        List<String> out = new ArrayList<>();
        for (int col = 0; col < TileArt.SIZE; col++) {
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec want = art.get(col, row);
                if (want == null) continue;
                BlockSpec have = r.world.getOrDefault(cell(r, col, row), BlockSpec.AIR);
                if (TopFaceRules.equivalent(want, have)) continue;
                if (!seen.contains(key(col, row))) out.add(col + "," + row + " " + want.id());
            }
        }
        return out;
    }

    static boolean wall(TileRunner r, int col, int row) {
        BlockSpec b = r.world.getOrDefault(cell(r, col, row), BlockSpec.AIR);
        return WALLS.contains(b.id());
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Lane 9's cells (columns 81-89) placed by 00:42:05 (from the replay), rows 0-61; '#' = placed. */
    static final String[] AT_0042 = {
        ".........", ".........", ".........", ".........", ".........", ".........", ".........", ".........",
        ".........", ".........", ".........", ".........", ".........", ".........", ".........", ".........",
        ".........", ".........", ".........", ".........", ".........", ".........", ".........", ".........",
        ".........", ".........", ".........", ".........", ".........", ".........", ".........", ".........",
        ".........", ".........", ".........", ".......#.", ".....####", "...######", "..#######", ".########",
        ".########", ".########", ".########", ".########", "...######", ".########", "#########", "#########",
        "#########", ".#.######", "...######", ".########", ".########", ".########", "..#######", ".########",
        "#########", "#########", "#########", "#########", "#########", "###...###"};

    /** The world at 00:42:05 and the bot on its stand spot; the rest of lane 9 from stand spots, as live. */
    static TileRunner fromTheReplay(TileArt art, boolean guard) {
        TileRunner r = liveState(art);
        r.build.laneKeepReachable = guard;
        r.build.laneOpenSegment = false; // replay the stand-spot path after the live walk had given up
        for (int row = 0; row < AT_0042.length; row++) {
            for (int i = 0; i < 9; i++) {
                BlockSpec b = art.get(81 + i, row);
                if (AT_0042[row].charAt(i) == '#' && b != null) r.world.put(cell(r, 81 + i, row), b);
            }
        }
        Lane full = LaneReentryTest.lane(r, art, 9);
        List<Placement> ps = new ArrayList<>(full.placements());
        ps.sort((a, b) -> Integer.compare(b.pos().z(), a.pos().z()));
        // the line is walled (the walk had given up): every cell from stand spots, from where the bot stood
        Lane l = new Lane(9, cell(r, 85, 56), cell(r, 85, -1), List.copyOf(ps));
        r.sim.teleport(r.geo.minX() + 83.45, r.geo.artY(), r.geo.minZ() + 50.59);
        List<Pos> order = new ArrayList<>();
        r.sim.onAccept = p -> order.add(p.pos());
        TaskStatus st = r.sim.runLane(l, r.params(), NONE, 4000 + 400 * l.placements().size());
        System.out.println("from the replay, keepReachable " + guard + ": " + st + " " + r.sim.log.getLast()
            + "; placed first " + order.subList(0, Math.min(6, order.size())) + "; enclosed " + enclosed(r, art)
            + "; skips " + r.sim.skipReasons + " " + r.sim.log.stream().filter(x -> x.contains("skipped [")).toList());
        assertEquals(TaskStatus.SUCCESS, st);
        return r;
    }

    /** (col, row) holds placed dripstone. */
    static boolean dripstone(TileRunner r, int col, int row) {
        return r.world.getOrDefault(cell(r, col, row), BlockSpec.AIR).id().equals("minecraft:pointed_dripstone");
    }

    /**
     * As live: the bot places (81,54) and (81,53) from its stand spot, then sticks at (81,50) ("12 stand spots in a
     * row placed nothing", live 00:43:46 with the bot at the same cell): (81,55) is left as a hole with placed
     * dripstone on all four sides.
     */
    @Test
    void withoutTheGuardTheHoleIsShutInAsLive() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = fromTheReplay(art, false);
        assertFalse(dripstone(r, 81, 55), "the hole is not placed");
        for (int[] n : new int[][]{{80, 55}, {82, 55}, {81, 54}, {81, 56}}) {
            assertTrue(dripstone(r, n[0], n[1]), "shut in by " + n[0] + "," + n[1]);
        }
        assertTrue(enclosed(r, art).contains("81,55 minecraft:pointed_dripstone"));
        assertTrue(r.sim.log.stream().anyMatch(s -> s.contains("12 stand spots in a row placed nothing (the bot at"
            + " [17,64,-14])")), "stuck at (81,50) as live");
    }

    @Test
    void withTheGuardNoCellIsShutIn() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = fromTheReplay(art, true);
        assertTrue(dripstone(r, 81, 55), "the hole is placed");
        assertTrue(enclosed(r, art).isEmpty(), "shut in: " + enclosed(r, art));
    }

    /** The whole second pass from row 62, as live: the walk sticks at row 58 and the field goes to stand spots. */
    @Test
    void theSecondPassLeavesNoHole() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        for (boolean guard : new boolean[]{false, true}) {
            TileRunner r = liveState(art);
            r.build.laneKeepReachable = guard;
            Lane l = lane9(r, art);
            r.sim.teleport(r.geo.minX() + 76.5, r.geo.artY(), r.geo.minZ() + 72.5);
            int from = r.sim.log.size();
            TaskStatus st = r.sim.runLane(l, r.params(), NONE, 4000 + 400 * l.placements().size());
            List<String> log = r.sim.log.subList(from, r.sim.log.size());
            List<String> enclosed = enclosed(r, art);
            List<String> left = new ArrayList<>();
            Lane rest = LaneReentryTest.lane(r, art, 9);
            if (rest != null) for (Placement p : rest.placements()) left.add(p.pos().toString());
            System.out.println("second pass, keepReachable " + guard + ": " + st + " " + log.getLast() + "; left "
                + left.size() + ", enclosed " + enclosed);
            assertEquals(TaskStatus.SUCCESS, st);
            assertTrue(String.join("\n", log).contains("cells stuck at 21.6,-5.6"), "stuck at row 58 as live");
            if (guard) {
                assertTrue(enclosed.isEmpty(), "shut in: " + enclosed);
                assertTrue(left.isEmpty(), "all placed: " + left);
                assertTrue(r.sim.reachable(r.geo.minX() + 85, r.geo.minZ() + 1), "the bot is not walled in");
            } else {
                assertFalse(left.isEmpty(), "left to VERIFY as live: " + log.getLast());
            }
        }
    }

    /** The kill switch reaches the lane parameters; BUILD lanes never break in. */
    @Test
    void killSwitchReachesTheParams() {
        TileRunner r = new TileRunner(1);
        r.build.laneKeepReachable = false;
        assertFalse(r.params().keepReachable());
        r.build.laneKeepReachable = true;
        assertTrue(r.params().keepReachable());
        assertFalse(r.params().breakIn(), "BUILD lanes never break in");
    }
}
