package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla's move ({@code Entity.collide}): the height first, then the longer horizontal axis, then the other, so the
 * box sweeps an L from where it was to where it ends, not the straight line. Local 2026-10-25 14:44 (5x, c23f851): a
 * short landing's arc onto a chest stand, its final line about 20 degrees off an axis; on the brake's first tick
 * (1.71 b/t, 1.64 over the stand's floor) the box met the corner of a glass pillar 3 high beside the final line, 0.06
 * inside the L's way across: 0.57 b/t left, 4-5 HP of fly_into_wall. {@link FlightSim} checks the L
 * ({@link FlightSim#wallOnMove}); {@code endBoxOnly} is the old check.
 */
class FlightCornerTest {
    private static final int[][] SERVERS = {{2, 31, 2}, {4, 25, 3}, {8, 20, 6}};

    /** The L's corner: a move mostly along x with a little z first sweeps x at the old z. */
    @Test
    void theBoxSweepsAnLNotTheStraightLine() {
        FlightSim w = new FlightSim().open();
        // a pillar at cell (11, 173..175, 11): the move from (9.8, 173.2, 11.45) to (11.4, 173.2, 10.6) passes by its
        // corner on the straight line (the box's far edge in z at x 10.7 is already under 11), but along x first at z
        // 11.45 the box (z 11.15-11.75) runs into its side
        for (int y = 173; y <= 175; y++) w.block(11, y, 11);
        assertTrue(w.wallOnMove(9.8, 11.45, 11.4, 10.6, 173.2, 0.6, 0));
        // the old check, the box where the move ends (x 11.1-11.7, z 10.3-10.9): clear of the pillar
        assertFalse(w.wallAt(11.4, 173.2, 10.6, 0.6, 0));
        // z first when the z part is longer: along z at x 9.8 (box x 9.5-10.1), then along x at z 9.4: clear
        assertFalse(w.wallOnMove(9.8, 11.45, 10.6, 9.4, 173.2, 0.6, 0));
    }

    /**
     * Short landings and arcs onto the walkway's chest stands over the built art, a pillar of 3 full blocks put beside
     * the plan's final line 4 to 9 blocks before its end, 0.45 to 1.05 off it (the box's half width is 0.3): no flight
     * meets a block at speed (fly_into_wall), and no landing touches one, whatever the boost and the server. The planner
     * may take another way or walk. A bail-out may touch one slowly in at most one case in a hundred, as in the arc
     * sweeps (a burn's spin over the chest stand 2.5 from the sorter wall, the boost ending at 1.2 b/t toward it: the
     * same without the pillar).
     */
    @Test
    void aPillarBesideTheFinalLineIsNeverMet() {
        record C(int goalX, int goalZ, int dist, int bearing, double before, double off, int life, int[] srv) { }
        List<C> cases = new ArrayList<>();
        for (int[] g : new int[][]{{20, -3}, {40, -2}, {64, -2}}) {
            for (int dist : new int[]{70, 90, 110}) {
                for (int bearing : new int[]{12, 20, 28, 36}) {
                    for (double before : new double[]{4, 6.5, 9}) {
                        for (double off : new double[]{-1.05, -0.75, -0.45, 0.45, 0.75, 1.05}) {
                            for (int life : new int[]{40, 53}) {
                                for (int[] srv : SERVERS) cases.add(new C(g[0], g[1], dist, bearing, before, off, life, srv));
                            }
                        }
                    }
                }
            }
        }
        record O(C c, String end, boolean pillar) { }
        List<O> out = cases.parallelStream().map(c -> {
            double a = Math.toRadians(c.bearing());
            double sx = c.goalX() + 0.5 + c.dist() * Math.cos(a);
            double sz = c.goalZ() + 0.5 + c.dist() * Math.sin(a);
            if (sx > 126 || sz > 126) return null;
            FlightSim probe = world();
            FlightSim.Flight p = probe.fly(sx, probe.floorAt(sx, 174.0, sz), sz, c.goalX(), 174, c.goalZ(),
                FlightPlanner.Cfg.defaults(60), FlightControl.Cfg.forDuration(3, 3, 1, 53), 4, 46, 3);
            if (probe.lastPlan == null || p.why().startsWith("walked: ") || p.why().startsWith("refused: ")) return null;
            FlightPlanner.Profile pr = probe.lastPlan.profile();
            double dx = pr.dirX;
            double dz = pr.dirZ;
            double px = pr.restX - dx * c.before() - dz * c.off();
            double pz = pr.restZ - dz * c.before() + dx * c.off();
            int cx = (int) Math.floor(px);
            int cz = (int) Math.floor(pz);
            FlightSim w = world();
            int base = (int) Math.floor(w.floorAt(cx + 0.5, 176.0, cz + 0.5) + 1e-6);
            if (pr.pad(cx, cz) || Math.hypot(cx + 0.5 - (c.goalX() + 0.5), cz + 0.5 - (c.goalZ() + 0.5)) < 1.5) return null;
            for (int y = base; y < base + 3; y++) w.block(cx, y, cz);
            FlightSim.Flight f = w.fly(sx, w.floorAt(sx, 174.0, sz), sz, c.goalX(), 174, c.goalZ(),
                FlightPlanner.Cfg.defaults(60), FlightControl.Cfg.forDuration(3, 3, 1, 53), c.srv()[0], c.life(), c.srv()[2]);
            if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) return new O(c, "walked", true);
            String how = String.format("goal %d,%d %d blocks at %d degrees, pillar %.1f before R %.2f off, life %d delay %d"
                + " (%s)", c.goalX(), c.goalZ(), c.dist(), c.bearing(), c.before(), c.off(), c.life(), c.srv()[0], f.why());
            if (w.lastFastContacts > 0 || f.wall()) return new O(c, how + ": ran into a block, fly_into_wall", true);
            if (w.lastContacts > 0 && f.why().equals("landed")) {
                return new O(c, how + ": touched a block in the air " + w.lastContacts + " times", true);
            }
            if (w.lastContacts > 0) return new O(c, "a bail-out touched a block slowly", true);
            if (f.maxFall() > 1.0 + 1e-9) return new O(c, how + ": fall distance " + f.maxFall(), true);
            if (f.touchVy() <= -0.5) return new O(c, how + ": sink " + f.touchVy(), true);
            return new O(c, w.lastSpot ? "flown onto a spot" : "flown onto a pad", true);
        }).filter(java.util.Objects::nonNull).toList();
        Map<String, Integer> ends = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        for (O o : out) {
            if (o.end().startsWith("goal ")) bad.add(o.end());
            else ends.merge(o.end(), 1, Integer::sum);
        }
        System.out.printf("a pillar beside the final line: %d cases, %d unsafe; %s%n", out.size(), bad.size(), ends);
        bad.stream().limit(30).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(out.size() > 300, "cases " + out.size());
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        // as the arc sweeps' bail-outs: a slow touch (no fly_into_wall) in at most one in a hundred
        int slow = ends.getOrDefault("a bail-out touched a block slowly", 0);
        assertTrue(slow * 100 <= out.size(), slow + " bail-outs of " + out.size() + " touched a block slowly");
    }

    private static FlightSim world() {
        FlightSim w = new FlightSim().lights().gated().spots(3);
        w.art(40, 40);
        w.arcMin = 40;
        return w;
    }
}
