package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hole guard ({@code build.edgeHoles}, {@link EdgeApproach#guard}, {@link EdgeDrop#holeAt},
 * {@link EdgeDrop#cornerCut}) on the live 2026-10-27 geometry (soul sand, tile-relative): the floor at y173 (feet level
 * 174), the sorter's 1x1 item shaft at x 126, z -7, open down to y170 (its bottom block y169: a drop of 4). After a login
 * the bot stood east of it in cell 127, -7; the walk went west past it, the feet were logged in the shaft's own cell
 * ("Edge stop: at (126,174,-7) moving 0.05 blocks a tick toward …; sneaking while it does") and it fell 4 blocks in.
 */
class EdgeHoleTest {
    static final int LEVEL = 174;
    static final int HX = 126;
    static final int HZ = -7;
    static final double WALK = 0.098;
    static final double SOUL_SPEED = NukeGuard.accel(0.49);

    /** Stone floor x 110..140, z -20..6 at y173, the shaft at (126, -7) open from y173 down to y170. */
    static final class World implements EdgeDrop.Cells {
        final Map<Pos, BlockSpec> blocks = new HashMap<>();
        final Set<Pos> water = new HashSet<>();
        final Set<Pos> noGo = new HashSet<>();

        World() {
            for (int x = 110; x <= 140; x++)
                for (int z = -20; z <= 6; z++) {
                    blocks.put(new Pos(x, 169, z), BlockSpec.of("minecraft:stone"));
                    if (x != HX || z != HZ) blocks.put(new Pos(x, 173, z), BlockSpec.of("minecraft:stone"));
                }
        }

        @Override public boolean loaded(Pos p) { return p.x() >= 110 && p.x() <= 140 && p.z() >= -20 && p.z() <= 6; }
        @Override public BlockSpec block(Pos p) { return blocks.getOrDefault(p, BlockSpec.AIR); }
        @Override public boolean fluid(Pos p) { return water.contains(p); }
        @Override public double collisionTop(BlockSpec b) { return b == null || b.isAir() ? 0 : 1.0; }
        @Override public boolean noGo(Pos feet) { return noGo.contains(feet) || noGo.contains(feet.above()); }

        /** A floor under the column (x, z) at the feet level. */
        boolean floor(int x, int z) {
            return collisionTop(block(new Pos(x, LEVEL - 1, z))) > 0;
        }

        /** The 0.6-wide box at feet (x, z) rests on some floor block. */
        boolean supported(double x, double z) {
            double h = Aim.PLAYER_HALF_WIDTH - 1e-7;
            for (int cx = (int) Math.floor(x - h); cx <= (int) Math.floor(x + h); cx++)
                for (int cz = (int) Math.floor(z - h); cz <= (int) Math.floor(z + h); cz++)
                    if (floor(cx, cz)) return true;
            return false;
        }
    }

    @Test
    void theShaftIsAHoleAndItsRimIsNot() {
        World w = new World();
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(HX, LEVEL, HZ), w));
        assertNull(EdgeDrop.holeAt(new Pos(HX + 1, LEVEL, HZ), w), "the rim cell, a floor");
        assertNull(EdgeDrop.holeAt(new Pos(HX, LEVEL, HZ + 1), w), "the stand moved onto the rim");
        // a drop of 3 (the pathfinder falls it) is no hole; the edge stops sneak there as before
        w.blocks.put(new Pos(HX, 170, HZ), BlockSpec.of("minecraft:stone"));
        assertNull(EdgeDrop.holeAt(new Pos(HX, LEVEL, HZ), w));
        w.blocks.remove(new Pos(HX, 170, HZ));
        // water below is one, a no-go zone over a floor is not (the zones guard their own cells), over the shaft it is
        w.blocks.remove(new Pos(130, 173, -7));
        w.water.add(new Pos(130, 172, -7));
        assertEquals("water or lava 2 below", EdgeDrop.holeAt(new Pos(130, LEVEL, -7), w));
        w.noGo.add(new Pos(132, LEVEL, -7));
        assertNull(EdgeDrop.holeAt(new Pos(132, LEVEL, -7), w));
        w.noGo.add(new Pos(HX, LEVEL, HZ));
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(HX, LEVEL, HZ), w));
        // a column not loaded: not known, no hole
        assertNull(EdgeDrop.holeAt(new Pos(141, LEVEL, HZ), w));
    }

    @Test
    void aDiagonalMovePastTheShaftsCornerIsFound() {
        World w = new World();
        assertNull(EdgeDrop.cornerCut(List.of(new Pos(127, LEVEL, -6), new Pos(127, LEVEL, -5)), w, p -> true),
            "a straight move");
        // from east of the shaft north-west to (126, -8): the move's side cell (126, -7) is the shaft
        EdgeDrop.Corner c = EdgeDrop.cornerCut(List.of(new Pos(127, LEVEL, -7), new Pos(126, LEVEL, -8)), w, p -> true);
        assertNotNull(c);
        assertEquals(new Pos(HX, LEVEL, HZ), c.hole());
        assertEquals("a drop of more than 3 blocks", c.hazard());
        assertNotNull(EdgeDrop.cornerCut(List.of(new Pos(125, LEVEL, -6), new Pos(127, LEVEL, -6), new Pos(127, LEVEL, -7),
            new Pos(126, LEVEL, -8)), w, p -> true), "the move later in the path");
        assertNull(EdgeDrop.cornerCut(List.of(new Pos(127, LEVEL, -7), new Pos(126, LEVEL, -8)), w, p -> false),
            "build.edgeHoles off, or a stair box");
        assertNull(EdgeDrop.cornerCut(List.of(new Pos(128, LEVEL, -7), new Pos(127, LEVEL, -8)), w, p -> true),
            "a diagonal one cell further east touches no hole");
        // a path that falls down the shaft itself (a planned fall): its column is the path's, no corner
        assertNull(EdgeDrop.cornerCut(List.of(new Pos(127, LEVEL, -7), new Pos(126, LEVEL, -8), new Pos(HX, 170, HZ)), w,
            p -> true));
    }

    @Test
    void theGuardTurnsHoldsOrBrakesAtTheRim() {
        World w = new World();
        // 0.2 east of the shaft, still, the keys west (yaw 90: forward is -x): one tick and its slide go 0.22
        assertNotNull(EdgeApproach.guard(127.2, -6.5, LEVEL, 0, 0, WALK, 90, 1, 0, c -> EdgeDrop.holeAt(c, w)),
            "walking west from 0.2 east of the shaft would go into it");
        EdgeApproach.Guard away = EdgeApproach.guard(127.5, -6.5, LEVEL, 0, 0, WALK, -90, 1, 0, c -> EdgeDrop.holeAt(c, w));
        assertNull(away, "walking east, away from it");
        // fast toward it from 1.5 east (Soul Speed): turned aside (the slide then ends 0.08 short of it)
        EdgeApproach.Guard g = EdgeApproach.guard(128.5, -6.5, LEVEL, -0.49, 0, SOUL_SPEED, 90, 1, 0,
            c -> EdgeDrop.holeAt(c, w));
        assertNotNull(g);
        assertTrue(g.keys());
        assertEquals("126,174,-7: a drop of more than 3 blocks; turned", g.why());
        assertTrue(g.forward() == 1 && g.strafe() != 0, "forward and to a side: " + g);
        // from 1.0 east at the same speed even no key slides it in: braking against the slide
        EdgeApproach.Guard b = EdgeApproach.guard(128.0, -6.5, LEVEL, -0.49, 0, SOUL_SPEED, 90, 1, 0,
            c -> EdgeDrop.holeAt(c, w));
        assertNotNull(b);
        assertEquals("126,174,-7: a drop of more than 3 blocks; braking", b.why());
        assertEquals(-1, b.forward(), "back against the slide: " + b);
        // already with the feet in the shaft's cell (a login there, a slide): sneak, the keys out are kept
        EdgeApproach.Guard in = EdgeApproach.guard(126.9, -6.5, LEVEL, 0, 0, WALK, -90, 1, 0, c -> EdgeDrop.holeAt(c, w));
        assertNotNull(in);
        assertTrue(in.sneak());
        assertFalse(in.keys(), "east, out of it: Baritone's own keys");
        // a planned fall down the shaft: its column is not a hole for the guard
        Set<Long> planned = EdgeApproach.planned(List.of(new Pos(127, LEVEL, -7), new Pos(HX, 170, HZ)), LEVEL);
        assertNull(EdgeApproach.guard(127.5, -6.5, LEVEL, 0, 0, WALK, 90, 1, 0,
            c -> planned.contains(EdgeDrop.column(c.x(), c.z())) ? null : EdgeDrop.holeAt(c, w)));
    }


    /**
     * Straight at goals west beyond the shaft from where the login left the bot (any spot of cell 127, -7), at walking
     * speed and at Soul Speed; vanilla's sneak edge stop is played (each axis' move shortened by 0.05 while the moved
     * box would rest on nothing). With the edge stops alone (sneak beside a hazard of the feet cell while moving toward
     * it, as live) the box drops into the shaft: once the feet point is in the shaft's own cell its neighbours are all
     * floor, nothing sneaks, and the box slides wholly over the hole. With the hole guard the feet point never goes
     * into the shaft's cell and the bot never falls (a goal right behind the shaft is not reached by steering straight:
     * the walker's paths go around it).
     */
    @Test
    void walkingStraightPastTheShaftNeverDropsTheBotIn() {
        World w = new World();
        int runs = 0;
        int fellBefore = 0;
        int fellAfter = 0;
        int enteredAfter = 0;
        for (double accel : new double[]{WALK, SOUL_SPEED})
            for (int sx = 0; sx < 4; sx++)
                for (int sz = 0; sz < 4; sz++)
                    for (int gx = 118; gx <= 123; gx++)
                        for (int gz = -10; gz <= -4; gz++) {
                            double x0 = 127.2 + 0.2 * sx;
                            double z0 = -6.8 + 0.2 * sz;
                            List<double[]> to = List.<double[]>of(new double[]{gx + 0.5, gz + 0.5});
                            runs++;
                            if (walk(w, x0, z0, to, accel, false).fell) fellBefore++;
                            Run after = walk(w, x0, z0, to, accel, true);
                            if (after.fell) fellAfter++;
                            if (after.entered) enteredAfter++;
                        }
        assertTrue(walk(w, 127.5, -6.5, List.<double[]>of(new double[]{118.5, -6.7}), WALK, false).fell,
            "east of the shaft, heading west past it: the live fall without the guard");
        assertTrue(fellBefore > 0, "falls without the guard: " + fellBefore + " of " + runs);
        assertEquals(0, fellAfter, "falls with the guard, of " + runs);
        assertEquals(0, enteredAfter, "runs whose feet went into the shaft's cell with the guard, of " + runs);
    }

    /**
     * The walk as the pathfinder plans it (feet cells, diagonal moves; a move's keys aim at the centre of its end cell
     * until the feet are in it), from where the login left the bot to goals west, north-west and south-west. As live,
     * a path may go diagonally past the shaft's corner, and the feet point crosses the shaft's cell. With the hole
     * guard the planned path goes around the corner ({@link EdgeDrop#cornerCut}: the walker plans again) and the keys
     * keep the feet out: every walk reaches its goal and none enters the shaft's cell.
     */
    @Test
    void plannedWalksPastTheShaftKeepTheFeetOut() {
        World w = new World();
        int runs = 0;
        int enteredBefore = 0;
        int enteredAfter = 0;
        int fellAfter = 0;
        int reachedAfter = 0;
        for (double accel : new double[]{WALK, SOUL_SPEED})
            for (int sx = 0; sx < 4; sx++)
                for (int sz = 0; sz < 4; sz++)
                    for (int gx = 120; gx <= 125; gx++)
                        for (int gz = -10; gz <= -4; gz++) {
                            double x0 = 127.1 + 0.25 * sx;
                            double z0 = -6.9 + 0.25 * sz;
                            Pos goal = new Pos(gx, LEVEL, gz);
                            runs++;
                            List<Pos> old = path(w, new Pos(127, LEVEL, -7), goal, false);
                            assertNotNull(old);
                            if (walk(w, x0, z0, centres(old), accel, false).entered) enteredBefore++;
                            List<Pos> now = path(w, new Pos(127, LEVEL, -7), goal, true);
                            assertNotNull(now);
                            assertNull(EdgeDrop.cornerCut(now, w, p -> true), "the planned path again: " + now);
                            Run after = walk(w, x0, z0, centres(now), accel, true);
                            if (after.entered) enteredAfter++;
                            if (after.fell) fellAfter++;
                            if (after.reached) reachedAfter++;
                        }
        assertTrue(enteredBefore > 0, "the feet in the shaft's cell, as logged live: " + enteredBefore + " of " + runs);
        assertEquals(0, enteredAfter, "with the guard, of " + runs);
        assertEquals(0, fellAfter, "with the guard, of " + runs);
        assertEquals(runs, reachedAfter, "goals reached with the guard");
    }

    record Run(boolean fell, boolean entered, boolean reached) { }

    private static List<double[]> centres(List<Pos> path) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) out.add(new double[]{path.get(i).x() + 0.5, path.get(i).z() + 0.5});
        return out;
    }

    /**
     * The pathfinder's path over the floor (straight moves cost 1, diagonal ones √2 with both side cells open at the
     * feet, as it plans them over a hole's corner); {@code holes}: the walker's corner check, a diagonal move past a
     * hole is not walked ({@link EdgeDrop#cornerCut}).
     */
    static @Nullable List<Pos> path(World w, Pos from, Pos to, boolean holes) {
        Map<Pos, Double> cost = new HashMap<>();
        Map<Pos, Pos> prev = new HashMap<>();
        PriorityQueue<Pos> open = new PriorityQueue<>(Comparator.comparingDouble(cost::get));
        cost.put(from, 0.0);
        open.add(from);
        while (!open.isEmpty()) {
            Pos p = open.poll();
            if (p.equals(to)) break;
            for (int[] d : EdgeDrop.AROUND) {
                Pos n = p.offset(d[0], 0, d[1]);
                if (!w.loaded(n) || !w.floor(n.x(), n.z())) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && holes && EdgeDrop.cornerCut(List.of(p, n), w, c -> true) != null) continue;
                double c = cost.get(p) + (diag ? Math.sqrt(2) : 1);
                if (c < cost.getOrDefault(n, Double.MAX_VALUE) - 1e-9) {
                    open.remove(n);
                    cost.put(n, c);
                    prev.put(n, p);
                    open.add(n);
                }
            }
        }
        if (!cost.containsKey(to)) return null;
        List<Pos> out = new ArrayList<>();
        for (Pos p = to; p != null; p = prev.get(p)) out.addFirst(p);
        return out;
    }

    /**
     * One walk through the points {@code to} (each a cell's centre but the straight walk's goal): the forward key at the
     * yaw facing the next point, on to the one after once the feet are in its cell (the straight walk: within 0.3); at
     * most 400 ticks. With {@code guarded} the hole guard acts on the keys and the sneak as in
     * {@link EdgeApproach#applyToBaritone}.
     */
    static Run walk(World w, double x, double z, List<double[]> to, double accel, boolean guarded) {
        double vx = 0;
        double vz = 0;
        boolean entered = false;
        int next = 0;
        for (int t = 0; t < 400; t++) {
            double[] p = to.get(next);
            boolean cell = (int) Math.floor(x) == (int) Math.floor(p[0]) && (int) Math.floor(z) == (int) Math.floor(p[1]);
            boolean straight = to.size() == 1;
            if (straight ? Math.hypot(p[0] - x, p[1] - z) < 0.3 : cell) {
                if (++next >= to.size()) return new Run(false, entered, true);
                p = to.get(next);
            }
            double dx = p[0] - x;
            double dz = p[1] - z;
            double[] end = to.getLast();
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            Pos feet = new Pos((int) Math.floor(x), LEVEL, (int) Math.floor(z));
            String hazard = EdgeDrop.beside(feet, w);
            boolean toward = EdgeApproach.toward(EdgeDrop.directions(feet, w), vx, vz);
            boolean sneak = EdgeApproach.sneak(hazard, Math.hypot(end[0] - x, end[1] - z), false, toward);
            int f = 1;
            int s = 0;
            if (guarded) {
                EdgeApproach.Guard g = EdgeApproach.guard(x, z, LEVEL, vx, vz, accel, yaw, f, s, c -> EdgeDrop.holeAt(c, w));
                if (g != null) {
                    if (g.keys()) {
                        f = g.forward();
                        s = g.strafe();
                    }
                    sneak |= g.sneak();
                }
            }
            double[] m = f == 0 && s == 0 ? new double[]{0, 0} : Aim.keysToWorld(yaw, f, s);
            double l = Math.hypot(m[0], m[1]);
            double a = accel * (sneak ? 0.3 : 1);
            if (l > 0) {
                vx += a * m[0] / l;
                vz += a * m[1] / l;
            }
            double mx = vx;
            double mz = vz;
            if (sneak) {
                // vanilla maybeBackOffFromEdge: shorten each axis' move while the moved box would rest on nothing
                while (mx != 0 && !w.supported(x + mx, z)) mx = step(mx);
                while (mz != 0 && !w.supported(x, z + mz)) mz = step(mz);
                while (mx != 0 && mz != 0 && !w.supported(x + mx, z + mz)) {
                    mx = step(mx);
                    mz = step(mz);
                }
            }
            x += mx;
            z += mz;
            vx = mx * NukeGuard.FRICTION;
            vz = mz * NukeGuard.FRICTION;
            if ((int) Math.floor(x) == HX && (int) Math.floor(z) == HZ) entered = true;
            if (!w.supported(x, z)) return new Run(true, entered, false);
        }
        return new Run(false, entered, false);
    }

    private static double step(double d) {
        if (Math.abs(d) < 0.05) return 0;
        return d > 0 ? d - 0.05 : d + 0.05;
    }
}
