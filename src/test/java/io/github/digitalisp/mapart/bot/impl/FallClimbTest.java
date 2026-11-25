package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FallClimb} on the soul sand platform's water channels (tile-relative: the tile x 0..127, z 0..127, soul sand
 * floor y172 with its top at 172.875, art layer y173), as read from the world 2026-10-23: the east channel x128 and the
 * west channel x-1, 1 wide, glass floor y169, water y170. The tile's side of a channel: glass y170, hoppers y171, soul
 * sand y172; the outer side: glass y170..172, obsidian y173. Every 9 cells along a channel (z mod 9): 2 an oak wall
 * sign at y170 under a top slab at y171 with water over it at y172, 3 water under a top slab at y171 and water at y172,
 * 1 and 4 a dead coral fan at y172. No-go zones: the parked machines x128..135 and the turnaround x-8..-1 (y173..178),
 * the east channel itself x128 y169..172.
 */
class FallClimbTest {
    static final double REACH = 4.5;

    /** A fake world of named blocks. */
    static final class World implements FallClimb.Grid {
        final Map<Pos, String> blocks = new HashMap<>();
        final List<int[]> zones = new ArrayList<>();
        final Set<Pos> unloaded = new HashSet<>();
        /** The zones holding the start's feet cell, which the rescue may place into. */
        final Set<Integer> startZones = new HashSet<>();

        void set(int x, int y, int z, String b) {
            blocks.put(new Pos(x, y, z), b);
        }

        String at(Pos p) {
            return blocks.getOrDefault(p, "air");
        }

        int zone(int x0, int y0, int z0, int x1, int y1, int z1) {
            zones.add(new int[]{x0, y0, z0, x1, y1, z1});
            return zones.size() - 1;
        }

        int zoneOf(Pos p) {
            for (int i = 0; i < zones.size(); i++) {
                int[] b = zones.get(i);
                if (p.x() >= b[0] && p.x() <= b[3] && p.y() >= b[1] && p.y() <= b[4] && p.z() >= b[2] && p.z() <= b[5]) return i;
            }
            return -1;
        }

        void startAt(Pos feet) {
            startZones.clear();
            int z = zoneOf(feet);
            if (z >= 0) startZones.add(z);
        }

        @Override public boolean loaded(Pos p) { return !unloaded.contains(p); }

        @Override public double top(Pos p) {
            return switch (at(p)) {
                case "air", "water", "source", "sign", "fan" -> 0;
                case "soul_sand" -> 0.875;
                case "carpet" -> 1.0 / 16;
                default -> 1; // full blocks, top slabs, hoppers
            };
        }

        @Override public double outline(Pos p) {
            return switch (at(p)) {
                case "air", "water", "source" -> 0;
                case "carpet" -> 1.0 / 16;
                default -> 1;
            };
        }

        @Override public boolean fluid(Pos p) { return at(p).equals("water") || at(p).equals("source"); }

        @Override public boolean replaceable(Pos p) { return at(p).equals("air") || fluid(p); }

        @Override public boolean clickable(Pos p) {
            return switch (at(p)) {
                case "glass", "stone", "obsidian", "netherrack", "wool" -> true;
                default -> false; // soul sand (not a full collision cube), hoppers (block entity), slabs, signs, fans
            };
        }

        @Override public boolean placeAllowed(Pos p) {
            if (at(p).equals("source")) return false; // FallClimbGrid: a source broken again would leave air
            if (p.y() == 173 && p.x() >= 0 && p.x() <= 127 && p.z() >= 0 && p.z() <= 127) return false; // art layer
            int z = zoneOf(p);
            return z < 0 || startZones.contains(z);
        }

        @Override public boolean exit(Pos feet) {
            if (fluid(feet) || fluid(feet.above())) return false;
            if (top(feet) > FallExit.LOW || top(feet.above()) > 0) return false;
            if (top(feet.below()) <= 0 && top(feet) <= 0) return false;
            return zoneOf(feet) < 0 && zoneOf(feet.above()) < 0;
        }
    }

    /**
     * The tile edge x 0..127 (only a strip of it near the channels), both channels, z -4..40; north of the tile (z < 0)
     * the channels run on under a glass ceiling at y173, the tile's side solid up to it (no way out there).
     */
    static World channels(boolean artCarpets, boolean fullArtEdge) {
        World w = new World();
        for (int z = -4; z <= 40; z++) {
            for (int x = -4; x <= 131; x++) {
                if (x > 4 && x < 123) continue; // the middle of the tile plays no part
                w.set(x, 168, z, "stone");
                w.set(x, 169, z, "glass");
            }
            for (int x : new int[]{0, 1, 2, 3, 4, 123, 124, 125, 126, 127}) {
                for (int y = 169; y <= 171; y++) w.set(x, y, z, "stone");
                w.set(x, 172, z, "soul_sand");
                if (artCarpets) w.set(x, 173, z, "carpet");
            }
            if (fullArtEdge) {
                w.set(127, 173, z, "wool");
                w.set(0, 173, z, "wool");
            }
            // the tile's side of each channel
            w.set(127, 170, z, "glass");
            w.set(127, 171, z, "hopper");
            w.set(0, 170, z, "glass");
            w.set(0, 171, z, "hopper");
            // outer walls
            for (int y = 170; y <= 172; y++) {
                w.set(129, y, z, "glass");
                w.set(-2, y, z, "glass");
            }
            for (int x = 129; x <= 131; x++) w.set(x, 173, z, "obsidian");
            for (int x = -4; x <= -2; x++) w.set(x, 173, z, "obsidian");
            for (int c : new int[]{128, -1}) {
                w.set(c, 169, z, "glass");
                w.set(c, 170, z, "water");
                switch (Math.floorMod(z, 9)) {
                    case 2 -> {
                        w.set(c, 170, z, "sign");
                        w.set(c, 171, z, "slab");
                        w.set(c, 172, z, "source");
                    }
                    case 3 -> {
                        w.set(c, 171, z, "slab");
                        w.set(c, 172, z, "source");
                    }
                    case 1 -> { // each segment's source, flowing north from there
                        w.set(c, 170, z, "source");
                        w.set(c, 172, z, "fan");
                    }
                    case 4 -> w.set(c, 172, z, "fan");
                    default -> { }
                }
            }
            if (z < 0) {
                for (int x : new int[]{-1, 128}) w.set(x, 173, z, "glass");
                for (int x : new int[]{0, 1, 2, 3, 4, 123, 124, 125, 126, 127}) {
                    w.set(x, 172, z, "stone");
                    w.set(x, 173, z, "glass");
                }
            }
        }
        w.zone(128, 173, 0, 135, 178, 127); // the parked machines
        w.zone(-8, 173, 0, -1, 178, 127);   // the turnaround
        w.zone(128, 169, -5, 128, 172, 127); // the east channel
        return w;
    }

    static FallClimb.Plan plan(World w, Pos feet, double h) {
        w.startAt(feet);
        return FallClimb.plan(feet, h, Set.of(), w, REACH);
    }

    /** Every block placed is broken again, none in a cell the rescue may not use, the exit dry at the floor's level. */
    static void assertClean(World w, FallClimb.Plan p, int exitY) {
        Set<Pos> placed = new HashSet<>();
        Set<Pos> broken = new HashSet<>();
        for (FallClimb.Move m : p.moves()) {
            if (m.block() != null) {
                assertTrue(w.placeAllowed(m.block()), "placed into an allowed cell: " + m);
                assertTrue(w.replaceable(m.block()), "into air or water: " + m);
                assertTrue(w.clickable(m.support()) || placed.contains(m.support()),
                    "against a clickable block or an own one: " + m);
                placed.add(m.block());
            }
            broken.addAll(m.breaks());
        }
        assertEquals(placed, broken, "every own block broken again: " + p);
        assertTrue(p.stranded().isEmpty(), "nothing left: " + p);
        FallClimb.Move exit = p.exit();
        assertEquals(FallClimb.Kind.EXIT, exit.kind());
        assertTrue(exit.to().y() >= exitY, "the exit at the floor's level: " + p);
        assertTrue(w.exit(exit.to()), "a dry exit outside the zones: " + p);
    }

    @Test
    void outOfTheEastChannelWithTwoBlocksBrokenAgain() {
        World w = channels(true, false);
        Pos feet = new Pos(128, 170, 23); // 23 mod 9 = 5: a plain cell
        FallClimb.Plan p = plan(w, feet, 170.0);
        assertNotNull(p);
        assertEquals(2, p.blocks(), p.toString());
        assertClean(w, p, 173);
        assertEquals(127, p.exit().to().x(), "onto the tile, not over the machines: " + p);
        assertEquals(173, p.exit().to().y());
        // first a block into the cell beside along the channel, then one a level up against the outer glass
        FallClimb.Move first = p.moves().getFirst();
        assertEquals(FallClimb.Kind.STAIR, first.kind(), p.toString());
        assertEquals(170, first.block().y());
        FallClimb.Move second = p.moves().get(1);
        assertEquals(FallClimb.Kind.STAIR, second.kind(), p.toString());
        assertEquals(171, second.block().y());
        assertEquals(new Pos(129, 171, second.block().z()), second.support(), "the outer wall: " + p);
        assertEquals(Aim.WEST, second.face());
        assertEquals(List.of(first.block()), second.breaks(), "the first block from on top of the second: " + p);
        assertEquals(List.of(second.block()), p.exit().breaks(), "the second from the tile: " + p);
        for (FallClimb.Move m : p.moves())
            if (m.block() != null) assertEquals(128, m.block().x(), "only in the channel: " + p);
    }

    @Test
    void everyCellOfBothChannelsGetsOut() {
        World w = channels(true, false);
        for (int c : new int[]{128, -1}) {
            int tileEdge = c == 128 ? 127 : 0;
            for (int z = 0; z <= 36; z++) {
                int m = Math.floorMod(z, 9);
                if (m == 2 || m == 3) continue; // no room to stand at y170 under a slab
                Pos feet = new Pos(c, 170, z);
                FallClimb.Plan p = plan(w, feet, 170.0);
                assertNotNull(p, "a way out from " + feet.key());
                assertTrue(p.blocks() <= 3, feet.key() + ": " + p);
                assertClean(w, p, 173);
                assertEquals(tileEdge, p.exit().to().x(), feet.key() + ": " + p);
            }
        }
    }

    @Test
    void fromThePondOverASlabTheBotJustJumpsOut() {
        World w = channels(true, false);
        for (int z : new int[]{11, 12, 21}) { // 11 and 20 mod 9 = 2 (the sign below), 12 and 21 mod 9 = 3
            Pos feet = new Pos(128, 172, z);
            FallClimb.Plan p = plan(w, feet, 172.0);
            assertNotNull(p, feet.key());
            assertEquals(0, p.blocks(), p.toString());
            assertEquals(1, p.moves().size(), p.toString());
            assertEquals(new Pos(127, 173, z), p.exit().to());
            assertTrue(p.exit().jump());
        }
    }

    @Test
    void fullArtAtTheEdgeTakesAThirdBlock() {
        World w = channels(false, true);
        Pos feet = new Pos(128, 170, 24);
        FallClimb.Plan p = plan(w, feet, 170.0);
        assertNotNull(p);
        assertEquals(3, p.blocks(), p.toString());
        assertClean(w, p, 173);
        assertEquals(174, p.exit().to().y(), "onto the art block at the edge: " + p);
        for (FallClimb.Move m : p.moves())
            if (m.block() != null) assertTrue(m.block().y() <= 172, "never into the machines' zone: " + p);
    }

    @Test
    void theWestChannelMirrorsTheEast() {
        World w = channels(true, false);
        Pos feet = new Pos(-1, 170, 25); // 25 mod 9 = 7
        FallClimb.Plan p = plan(w, feet, 170.0);
        assertNotNull(p);
        assertEquals(2, p.blocks(), p.toString());
        assertClean(w, p, 173);
        assertEquals(new Pos(-2, 171, p.moves().get(1).block().z()), p.moves().get(1).support(), p.toString());
        assertEquals(Aim.EAST, p.moves().get(1).face());
    }

    @Test
    void underTheCeilingNorthOfTheTileItClimbsBackSouth() {
        World w = channels(true, false);
        for (int z = -3; z <= -1; z++) {
            Pos feet = new Pos(128, 170, z);
            FallClimb.Plan p = plan(w, feet, 170.0);
            assertNotNull(p, feet.key());
            assertClean(w, p, 173);
            assertTrue(p.exit().to().z() >= 0, "out where the tile is: " + p);
        }
    }

    @Test
    void neverIntoAnotherZoneNorTheArtLayer() {
        World w = channels(true, false);
        // a zone over the channel's upper cells that the start is not in: the stair a level up may not go there
        w.zone(128, 171, 20, 128, 172, 30);
        Pos feet = new Pos(128, 170, 24);
        FallClimb.Plan p = plan(w, feet, 170.0);
        if (p != null) {
            for (FallClimb.Move m : p.moves())
                if (m.block() != null) assertTrue(w.placeAllowed(m.block()), p.toString());
        }
        for (int z = 0; z < 40; z++) assertFalse(w.placeAllowed(new Pos(127, 173, z)), "the art layer");
    }

    @Test
    void aDryShaftIsPillaredAndThePillarBrokenFromTheTop() {
        World w = new World();
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                for (int y = 60; y <= 63; y++) w.set(x, y, z, "stone");
            }
        // a 1x1 shaft three deep: feet at y61 on stone at y60, the floor around at top 64
        w.set(0, 61, 0, "air");
        w.set(0, 62, 0, "air");
        w.set(0, 63, 0, "air");
        Pos feet = new Pos(0, 61, 0);
        FallClimb.Plan p = plan(w, feet, 61.0);
        assertNotNull(p);
        // two pillars: from feet 63 the floor (top 64) is a jump away
        assertEquals(2, p.blocks(), p.toString());
        for (int i = 0; i < 2; i++) assertEquals(FallClimb.Kind.PILLAR, p.moves().get(i).kind(), p.toString());
        assertClean(w, p, 64);
        assertEquals(List.of(new Pos(0, 62, 0), new Pos(0, 61, 0)), p.exit().breaks(),
            "top first, from the floor beside: " + p);
    }

    @Test
    void boxedInFourBlocksDownTheShaftUnderThePickupStandItPillarsBackUp() {
        // live 2026-10-27 (relative to the soul sand tile): from the stand (126, 174, -7) 4 blocks down a dry 1x1 shaft,
        // 7 cells north of the tile (no FallWatch there); the engine rescues it from the walk's boxed-in drop, floor y174
        World w = new World();
        for (int x = 120; x <= 132; x++)
            for (int z = -12; z <= -2; z++)
                for (int y = 165; y <= 173; y++) w.set(x, y, z, "stone");
        for (int y = 170; y <= 173; y++) w.set(126, y, -7, "air");
        Pos feet = new Pos(126, 170, -7);
        FallClimb.Plan p = plan(w, feet, 170.0);
        assertNotNull(p);
        assertEquals(3, p.blocks(), "three pillars: from feet 173 the ground (top 174) is a jump away: " + p);
        for (int i = 0; i < 3; i++) assertEquals(FallClimb.Kind.PILLAR, p.moves().get(i).kind(), p.toString());
        FallClimb.Move exit = p.exit();
        assertEquals(FallClimb.Kind.EXIT, exit.kind());
        assertEquals(174, exit.to().y(), "back at the level it dropped from: " + p);
        assertTrue(w.exit(exit.to()), p.toString());
    }

    @Test
    void aWiderWaterPitGetsOutBySteps() {
        World w = new World();
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                w.set(x, 59, z, "stone");
                boolean pit = Math.abs(x) <= 1 && Math.abs(z) <= 1;
                for (int y = 60; y <= 63; y++) w.set(x, y, z, pit ? (y == 60 ? "water" : "air") : "stone");
            }
        Pos feet = new Pos(0, 60, 0);
        FallClimb.Plan p = plan(w, feet, 60.0);
        assertNotNull(p);
        assertTrue(p.blocks() <= 4, p.toString());
        assertClean(w, p, 64);
    }

    @Test
    void tooDeepIsNoPlan() {
        World w = new World();
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++)
                for (int y = 50; y <= 63; y++) w.set(x, y, z, x == 0 && z == 0 && y > 50 ? "air" : "stone");
        assertNull(plan(w, new Pos(0, 51, 0), 51.0), "13 deep: beyond the blocks a plan may place");
    }

    @Test
    void neverIntoAWaterSource() {
        // local run 2026-10-23: a stair into the west channel's south end, the source of its last segment: broken again
        // it left air and the segment ran dry
        World w = channels(true, false);
        for (int c : new int[]{128, -1}) {
            for (int z = 0; z <= 36; z++) {
                if (Math.floorMod(z, 9) == 2 || Math.floorMod(z, 9) == 3) continue;
                FallClimb.Plan p = plan(w, new Pos(c, 170, z), 170.0);
                assertNotNull(p, c + "," + z);
                for (FallClimb.Move m : p.moves()) {
                    if (m.block() != null) assertFalse(w.at(m.block()).equals("source"), "into a source: " + p);
                }
            }
        }
    }

    @Test
    void neverAPillarFromWater() {
        // local run 2026-10-23: a cell boxed in by the slab to the north and a stray block over the water to the south;
        // the plan was two pillars from the water, whose jumps rose 0.36
        World w = channels(true, false);
        w.set(128, 171, 23, "stone");
        Pos feet = new Pos(128, 170, 22); // 21 mod 9 = 3: water under a slab to the north
        FallClimb.Plan p = plan(w, feet, 170.0);
        if (p != null) {
            for (FallClimb.Move m : p.moves()) {
                if (m.kind() == FallClimb.Kind.PILLAR) assertFalse(w.fluid(m.block()), "a pillar from water: " + p);
            }
        }
        // nor anywhere in the channels
        for (int z = 0; z <= 36; z++) {
            if (Math.floorMod(z, 9) == 2 || Math.floorMod(z, 9) == 3) continue;
            FallClimb.Plan q = plan(channels(true, false), new Pos(128, 170, z), 170.0);
            assertNotNull(q);
            for (FallClimb.Move m : q.moves()) {
                if (m.kind() == FallClimb.Kind.PILLAR) assertFalse(w.fluid(m.block()), "a pillar from water: " + q);
            }
        }
    }

    @Test
    void blocksOfAnEarlierTryAreUsedAndBrokenToo() {
        World w = channels(true, false);
        // an earlier try left a block in the channel beside the bot and fell back
        Pos left = new Pos(128, 170, 22);
        w.set(128, 170, 22, "netherrack");
        Pos feet = new Pos(128, 170, 23);
        w.startAt(feet);
        FallClimb.Plan p = FallClimb.plan(feet, 170.0, Set.of(left), w, REACH);
        assertNotNull(p);
        Set<Pos> broken = new HashSet<>();
        for (FallClimb.Move m : p.moves()) broken.addAll(m.breaks());
        assertTrue(broken.contains(left), "the earlier block goes too: " + p);
        assertTrue(p.stranded().isEmpty(), p.toString());
        assertTrue(p.blocks() <= 2, "it stands on the earlier block: " + p);
    }
}
