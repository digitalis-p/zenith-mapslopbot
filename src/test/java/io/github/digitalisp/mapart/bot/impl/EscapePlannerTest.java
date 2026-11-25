package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Open floor cells for the setback escape, and the path check of the walk there. */
class EscapePlannerTest {
    private static final Pos STUCK = new Pos(0, 64, 0);

    /** Collision tops by cell (air elsewhere), fluids, hazards, loaded and allowed cells. */
    private static final class World implements EscapePlanner.Cells {
        final Map<Pos, Double> tops = new HashMap<>();
        final Set<Pos> fluids = new HashSet<>();
        final Set<Pos> hazards = new HashSet<>();
        Predicate<Pos> loaded = p -> true;
        Predicate<Pos> allowed = p -> true;

        /** Full-block floor at y 63, x and z -20..20; feet level y 64. */
        static World flat() {
            World w = new World();
            for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) w.set(x, 63, z, 1.0);
            return w;
        }

        World set(int x, int y, int z, double top) {
            tops.put(new Pos(x, y, z), top);
            return this;
        }

        World block(int x, int y, int z) {
            return set(x, y, z, 1.0);
        }

        @Override public boolean isLoaded(Pos p) { return loaded.test(p); }
        @Override public double collisionTop(Pos p) { return tops.getOrDefault(p, 0.0); }
        @Override public boolean hasFluid(Pos p) { return fluids.contains(p); }
        @Override public boolean hazard(Pos p) { return hazards.contains(p); }
        @Override public boolean allowed(Pos feet) { return allowed.test(feet); }
    }

    private static EscapePlanner.Plan plan(World w, Pos bot, int rung, Set<Pos> avoid) {
        return EscapePlanner.plan(w, STUCK, bot, rung, avoid);
    }

    private static List<Pos> cells(EscapePlanner.Plan p) {
        return p.candidates().stream().map(EscapePlanner.Candidate::feet).toList();
    }

    private static int ring(Pos p) {
        return Math.max(Math.abs(p.x() - STUCK.x()), Math.abs(p.z() - STUCK.z()));
    }

    // ------------------------------------------------------------------------------------------------------ rings

    @Test
    void eachRungLooksFurtherOut() {
        int[][] rings = {{1, 2, 3, 40}, {2, 3, 5, 96}, {3, 4, 6, 120}, {4, 4, 6, 120}, {9, 4, 6, 120}};
        for (int[] r : rings) {
            EscapePlanner.Plan p = plan(World.flat(), STUCK, r[0], Set.of(STUCK));
            assertEquals(r[1], EscapePlanner.minRing(r[0]), "rung " + r[0]);
            assertEquals(r[2], EscapePlanner.maxRing(r[0]), "rung " + r[0]);
            assertEquals(r[3], p.candidates().size(), "rung " + r[0] + ": every floor cell of the ring, at the bot's level");
            for (Pos c : cells(p)) {
                assertTrue(ring(c) >= r[1] && ring(c) <= r[2], "rung " + r[0] + ": " + c);
                assertEquals(64, c.y(), "the cells one lower are the floor blocks themselves");
            }
        }
    }

    @Test
    void openFloorFirstNearestTheBotThenStable() {
        EscapePlanner.Plan p = plan(World.flat(), STUCK, 1, Set.of(STUCK));
        assertEquals(List.of(new Pos(-2, 64, 0), new Pos(0, 64, -2), new Pos(0, 64, 2), new Pos(2, 64, 0)),
            cells(p).subList(0, 4), "distance 2 on the axes, then x and z");
        for (EscapePlanner.Candidate c : p.candidates()) assertEquals(0, c.tallSides(), "open floor: " + c);
    }

    // ------------------------------------------------------------------------------------------- up and down

    /** Full blocks at y 64 for x 0..10, z -10..10 (placed art), floor elsewhere. */
    private static World blockField() {
        World w = World.flat();
        for (int x = 0; x <= 10; x++) for (int z = -10; z <= 10; z++) w.block(x, 64, z);
        return w;
    }

    @Test
    void fromTheTopOfBlocksTheBotMayStepDownOneButNoFurther() {
        World w = blockField();
        Pos bot = new Pos(0, 65, 0);
        EscapePlanner.Plan p = EscapePlanner.plan(w, new Pos(0, 65, 0), bot, 1, Set.of());
        List<Pos> got = cells(p);
        assertTrue(got.contains(new Pos(2, 65, 0)), "on top of the blocks, same level");
        assertTrue(got.contains(new Pos(-2, 64, 0)), "one lower, off the blocks");
        for (Pos c : got) assertTrue(c.y() == 65 || c.y() == 64, "level " + c);
        // same level before one lower at equal distance and walls
        assertEquals(List.of(new Pos(0, 65, -2), new Pos(0, 65, 2), new Pos(2, 65, 0), new Pos(-2, 64, 0)),
            got.subList(0, 4));
    }

    @Test
    void neverUpOntoTheBlocks() {
        World w = blockField();
        Pos bot = new Pos(-1, 64, 0);
        EscapePlanner.Plan p = EscapePlanner.plan(w, bot, bot, 1, Set.of(bot));
        assertFalse(p.candidates().isEmpty());
        for (Pos c : cells(p)) {
            assertEquals(64, c.y(), "never the open tops at y 65: " + c);
            assertTrue(c.x() < 0, "beside the field: " + c);
        }
        // the cells along the field's west edge have it as one wall
        assertTrue(p.candidates().stream().anyMatch(c -> c.tallSides() == 1));
        assertEquals(0, p.candidates().getFirst().tallSides(), "an open cell first");
    }

    // --------------------------------------------------------------------------------------- walls, pockets, slots

    @Test
    void pocketsAndOneWideSlotsAreRefusedOneWallIsFine() {
        World w = World.flat();
        w.block(2, 64, 1).block(2, 64, -1); // (2,64,0): walls north and south, a 1-wide slot
        w.block(1, 64, 2).block(0, 64, 3); // (0,64,2): walls east and south, a corner
        w.block(-3, 64, 0); // (-2,64,0): one wall west
        w.block(3, 65, -2).block(2, 64, -3); // (2,64,-2): a wall at head height east, one at the feet north
        w.set(-3, 64, 2, 0.5); // (-2,64,2): a slab beside it is a step, not a wall
        EscapePlanner.Plan p = plan(w, STUCK, 1, Set.of(STUCK));
        List<Pos> got = cells(p);
        assertFalse(got.contains(new Pos(2, 64, 0)), "slot");
        assertFalse(got.contains(new Pos(0, 64, 2)), "pocket");
        assertFalse(got.contains(new Pos(2, 64, -2)), "head-height wall + feet wall");
        EscapePlanner.Candidate west = p.candidates().stream().filter(c -> c.feet().equals(new Pos(-2, 64, 0)))
            .findFirst().orElseThrow();
        assertEquals(1, west.tallSides());
        assertTrue(p.candidates().stream().anyMatch(c -> c.feet().equals(new Pos(-2, 64, 2)) && c.tallSides() == 0),
            "slab beside");
        assertTrue(p.refused().get(EscapePlanner.SLOT) >= 1, p.refused().toString());
        assertTrue(p.refused().get(EscapePlanner.WALLED) >= 2, p.refused().toString());
        // fewer walls first, then nearer: the one-wall cells come after every open one
        for (int i = 1; i < p.candidates().size(); i++) {
            EscapePlanner.Candidate a = p.candidates().get(i - 1);
            EscapePlanner.Candidate b = p.candidates().get(i);
            assertTrue(a.tallSides() < b.tallSides() || a.tallSides() == b.tallSides() && a.distSq() <= b.distSq(),
                a + " before " + b);
        }
        assertTrue(got.indexOf(new Pos(-2, 64, 0)) > got.indexOf(new Pos(-3, 64, 3)), "one wall after open, though nearer");
    }

    @Test
    void theIncidentCellIsNotAnEscape() {
        // live 2026-10-11, burst 1: a floor cell walled east and south, with a 1-wide slot north of it
        World w = World.flat();
        Pos cell = new Pos(-3, 64, -3);
        Pos slot = new Pos(-3, 64, -4);
        w.block(-2, 64, -3).block(-3, 64, -2); // east, south
        w.block(-2, 64, -4).block(-4, 64, -4); // either side of the slot
        assertArrayEquals(new boolean[]{true, false, true, false}, EscapePlanner.walls(w, cell), "east, south");
        assertArrayEquals(new boolean[]{true, true, false, false}, EscapePlanner.walls(w, slot), "east, west");
        EscapePlanner.Plan p = plan(w, STUCK, 2, Set.of(STUCK));
        assertFalse(cells(p).contains(cell), "a corner");
        assertFalse(cells(p).contains(slot), "a slot");
        assertEquals(1, p.refused().get(EscapePlanner.SLOT), p.refused().toString());
    }

    // ---------------------------------------------------------------------------------------- setback cells

    @Test
    void neverWithinOneOfAnEarlierBurst() {
        World w = World.flat();
        Pos earlier = new Pos(3, 64, 0);
        EscapePlanner.Plan p = plan(w, STUCK, 1, Set.of(STUCK, earlier));
        for (Pos c : cells(p)) {
            int d = Math.max(Math.abs(c.x() - earlier.x()), Math.max(Math.abs(c.y() - earlier.y()), Math.abs(c.z() - earlier.z())));
            assertTrue(d >= 2, "next to the earlier burst: " + c);
        }
        assertEquals(12, p.refused().get(EscapePlanner.NEAR_SETBACK), "x 2..3, z -1..1 of the ring, both levels");
        assertEquals(34, p.candidates().size());
    }

    // ---------------------------------------------------------------------------------- what is not a floor cell

    @Test
    void unloadedFluidNoFloorBlockedAndZonesAreRefused() {
        World w = World.flat();
        w.loaded = p -> p.x() < 3;
        w.fluids.add(new Pos(0, 64, 2)); // water at the feet
        w.fluids.add(new Pos(0, 65, -2)); // at the head
        w.tops.remove(new Pos(-2, 63, 0)); // a hole
        w.set(-2, 63, 1, 1.0 / 16); // a carpet below: the bot would stand in that cell
        w.set(-2, 63, -1, 0.5); // a slab below: the same
        w.set(-3, 63, -3, 1.5); // a fence below
        w.hazards.add(new Pos(-3, 63, 3)); // magma
        w.set(-3, 64, 0, 1.0 / 16); // a carpet at the feet: fine
        w.set(-3, 64, 1, 0.5); // a slab at the feet: no
        w.block(-3, 65, -1); // a block at the head
        w.hazards.add(new Pos(-3, 64, -2)); // a cobweb at the feet
        w.allowed = p -> !(p.x() == 2 && p.z() == 2); // a no-go zone
        EscapePlanner.Plan p = plan(w, STUCK, 1, Set.of(STUCK));
        List<Pos> got = cells(p);
        for (Pos c : got) assertTrue(c.x() < 3, "unloaded: " + c);
        Pos[] refused = {new Pos(0, 64, 2), new Pos(0, 64, -2), new Pos(-2, 64, 0), new Pos(-2, 64, 1), new Pos(-2, 64, -1),
            new Pos(-3, 64, -3), new Pos(-3, 64, 3), new Pos(-3, 64, 1), new Pos(-3, 64, -1), new Pos(-3, 64, -2),
            new Pos(2, 64, 2)};
        for (Pos r : refused) assertFalse(got.contains(r), "refused: " + r);
        assertTrue(got.contains(new Pos(-3, 64, 0)), "a carpet at the feet is stood in");
        Map<String, Integer> r = p.refused();
        // both levels: y 64 (feet on the floor) and y 63 (feet in the floor blocks: blocked, except where noted)
        assertEquals(14, r.get(EscapePlanner.UNLOADED), "x 3, 7 cells on each level: " + r);
        assertEquals(3, r.get(EscapePlanner.FLUID), "feet, head, and the water as the head of 0,63,2: " + r);
        assertEquals(7, r.get(EscapePlanner.NO_FLOOR), "hole, carpet, slab, fence, magma; and at y 63 the hole and the"
            + " carpet themselves: " + r);
        assertEquals(2, r.get(EscapePlanner.NOT_ALLOWED), "the zone column, both levels: " + r);
        assertEquals(3 + 29, r.get(EscapePlanner.BLOCKED), "slab, cobweb, head block; 29 floor blocks: " + r);
        assertEquals(22, p.candidates().size());
        assertEquals(80, p.looked());
        assertTrue(p.candidates().stream().allMatch(c -> c.feet().x() != 2 || c.tallSides() == 1),
            "an unloaded side is a wall");
    }

    @Test
    void nothingOpenSaysWhy() {
        World w = new World(); // no floor anywhere
        EscapePlanner.Plan p = plan(w, STUCK, 1, Set.of(STUCK));
        assertTrue(p.candidates().isEmpty());
        assertEquals("no open floor cell within 3 of 0,64,0 (80 cells: 80 no floor)", p.none(STUCK));
    }

    // ------------------------------------------------------------------------------------------------ the walk

    @Test
    void pathsMayStepDownButNeverUp() {
        Pos far = new Pos(50, 64, 50);
        assertEquals("climbs onto a block at 2,64,0", EscapePlanner.pathRefusal(
            List.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 65, 0)), far, Set.of()));
        assertNull(EscapePlanner.pathRefusal(
            List.of(new Pos(0, 65, 0), new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 63, 0)), far, Set.of()));
        assertNull(EscapePlanner.pathRefusal(List.of(), far, Set.of()));
    }

    @Test
    void aPathLeavesTheSetbackCellAndNeverComesBack() {
        assertNull(EscapePlanner.pathRefusal(
            List.of(STUCK, new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 64, 0)), STUCK, Set.of(STUCK)), "out");
        assertNull(EscapePlanner.pathRefusal(
            List.of(STUCK, new Pos(1, 64, 1), new Pos(1, 64, 0), new Pos(2, 64, -1)), STUCK, Set.of(STUCK)),
            "round the corner while still leaving");
        assertEquals("passes the setback cell 0,64,0", EscapePlanner.pathRefusal(
            List.of(STUCK, new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(1, 64, 1)), STUCK, Set.of(STUCK)), "back");
        assertEquals("passes the setback cell 0,64,0", EscapePlanner.pathRefusal(
            List.of(new Pos(-1, 64, 0), STUCK, new Pos(1, 64, 0), new Pos(2, 64, 0)), STUCK, Set.of(STUCK)),
            "through it from beside it");
    }

    @Test
    void aPathKeepsClearOfEarlierBursts() {
        Pos earlier = new Pos(5, 64, 0);
        assertEquals("passes the setback cell 5,64,0", EscapePlanner.pathRefusal(
            List.of(STUCK, new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 64, 0), new Pos(4, 64, 1)), STUCK,
            Set.of(STUCK, earlier)));
        assertNull(EscapePlanner.pathRefusal(
            List.of(STUCK, new Pos(1, 64, 0), new Pos(2, 64, 0), new Pos(3, 64, 0), new Pos(3, 64, 2)), STUCK,
            Set.of(STUCK, earlier)), "two away");
        assertNull(EscapePlanner.pathRefusal(
            List.of(new Pos(3, 62, 0), new Pos(4, 62, 0), new Pos(5, 62, 0), new Pos(6, 62, 0)), STUCK,
            Set.of(STUCK, new Pos(5, 64, 0))), "two blocks under it");
    }
}
