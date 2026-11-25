package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2026-10-28 06:49:56 (`elytraShortLandings` on): a STAND_SPOT leg of 61 blocks (Lane 10's "last resort"
 * stand spot, a carpet cell in a field of full art blocks) flew an arc (17 straight, a turn of 142 degrees at radius
 * 14, 55 straight into R) onto a 2 x 2 carpet spot whose far side was gray terracotta and whose side across the final
 * line cobbled deepslate, both full blocks on the art layer. The replay: the brake started 1.65 over the carpet at 1.47
 * b/t, the bot came over T still 1.0 up at 0.86 b/t, touched down 1.8 past R and 0.8 across on the pocket's rim at
 * 0.48 b/t and took fly_into_wall damage (no "ran into a block": ZenithProxy's Bot met no block in the air). The art
 * around the goal as the replay's chunks had it (goal at its centre; T and D full blocks, carpets and plates are the
 * soul sand floor here), the start and the final heading as flown.
 */
class FlightPocketTest {

    /**
     * The art layer around the goal, rows z -6..6, columns x -6..6 (T, D: full blocks; else the floor). The end rods
     * over the tile ({@link FlightSim#lights}) keep the arc low, as live (1.5 to 4.4 over the floor).
     */
    private static final String[] POCKET = {
        "...DDDDTT....",
        "...DDDDDDTT..",
        "...DDDDDDTT..",
        ".....DDDDTT..",
        ".....DDDDTT..",
        ".......TT....",
        ".......TT....",
        "...TT..TT....",
        "...TT..TT....",
        "...TTTT......",
        "...TTTT......",
        ".............",
        "............."};

    private static final int GX = 60;
    private static final int GZ = 40;

    private static FlightSim world() {
        FlightSim w = new FlightSim().lights().gated().spots(0);
        w.arcMin = 60;
        for (int r = 0; r < POCKET.length; r++) {
            for (int c = 0; c < POCKET[r].length(); c++) {
                char ch = POCKET[r].charAt(c);
                if (ch == 'T' || ch == 'D') w.block(GX - 6 + c, 173, GZ - 6 + r);
            }
        }
        return w;
    }

    /** The live take-off spot relative to the goal: 5.7 east, 61.2 south. */
    private static final double SX = GX + 5.69;
    private static final double SZ = GZ + 61.22;

    @Test
    void theLivePocketSpotIsRefusedForTheOverrun() {
        FlightSim w = world();
        FlightPlanner.Terrain t = w.terrain();
        double sy = w.floorAt(SX, 174, SZ);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.defaults(60);
        // every arc onto a spot at the goal whose final line runs as the live one did (toward +x, -z) is refused
        FlightPlanner.ArcResult r = FlightPlanner.planArc(SX, sy, SZ, GX, 173, GZ, t, pc, 60, FlightPlanner.ARC_PATH_MIN, 0);
        int intoRim = 0;
        int refused = 0;
        for (FlightPlanner.Plan p : r.plans()) {
            FlightPlanner.Profile f = p.profile();
            boolean pocket = Math.abs(f.restX - (GX + 0.5)) <= 1.0 && Math.abs(f.restZ - (GZ + 0.5)) <= 1.0;
            if (pocket && f.dirX > 0.2 && f.dirZ < -0.2) {
                intoRim++;
                if (FlightPlanner.overrunProblem(t, f) != null) refused++;
            }
        }
        System.out.printf("pocket arcs: %d ways, %d into the rim, %d refused for the overrun%n", r.plans().size(), intoRim, refused);
        assertTrue(intoRim == refused, intoRim + " ways into the rim, " + refused + " refused");
        // the live way itself: R = T at the goal cell's corner whose 2 x 2 is all carpet, final heading as flown
        FlightPlanner.Profile live = profile(GX, GZ, 0.50, -0.866);
        String why = FlightPlanner.overrunProblem(t, live);
        assertNotNull(why);
        assertTrue(why.startsWith("a raised block"), why);
        // the same spot flown toward the open side (west, back the way the art is carpet) passes
        assertNull(FlightPlanner.overrunProblem(t, profile(GX, GZ, -1, 0.0)));
        // a straight flight's spot on open soul sand passes whatever the heading
        FlightSim open = new FlightSim();
        for (double[] d : new double[][]{{1, 0}, {0, 1}, {-0.6, -0.8}, {0.7071, 0.7071}}) {
            assertNull(FlightPlanner.overrunProblem(open.terrain(), profile(GX + 0.5, GZ + 0.5, d[0], d[1])));
        }
        // a spot on the walkway's south edge flown onto from the tile (a step of 1.1 up just short of T: the long-hop
        // stop whose brake bailed out in FlightLongHopTest) is refused; the same spot flown along the walkway passes
        FlightSim plat = new FlightSim();
        FlightPlanner.Profile edge = profile(93, -1, 0.58, -0.81, 174, 174.0);
        String step = FlightPlanner.overrunProblem(plat.terrain(), edge);
        assertNotNull(step);
        assertTrue(step.startsWith("a step of 1.1 up onto the spot"), step);
        assertNull(FlightPlanner.overrunProblem(plat.terrain(), profile(93, -2, 1, 0, 174, 174.0)));
    }

    /** A spot profile at T (tx, tz), final heading (dx, dz), its cells as {@link FlightPlanner#spotCells} gives them. */
    private static FlightPlanner.Profile profile(double tx, double tz, double dx, double dz) {
        return profile(tx, tz, dx, dz, 173, 172.875);
    }

    /** {@link #profile} at feet level {@code level} on a floor at {@code surface}. */
    private static FlightPlanner.Profile profile(double tx, double tz, double dx, double dz, int level, double surface) {
        Set<Long> pad = FlightPlanner.spotCells(tx, tz);
        FlightPlanner.Profile p = new FlightPlanner.Profile(Map.of(), Map.of(), pad, 190, tx - dx * 60, tz - dz * 60, tx,
            tz, level, surface);
        p.spot = true;
        return p;
    }

    @Test
    void theLiveLegLandsClearOfTheRimOrWalks() {
        // the live leg flown with the planner and the controller: boosts 40-56 against 52 planned, 3 servers
        int[][] servers = {{2, 2}, {4, 3}, {8, 6}};
        List<String> bad = new ArrayList<>();
        Map<String, Integer> ends = new TreeMap<>();
        int flown = 0;
        for (int life : new int[]{40, 45, 51, 56}) {
            for (int[] srv : servers) {
                FlightSim w = world();
                double sy = w.floorAt(SX, 174, SZ);
                FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
                FlightSim.Flight f = w.fly(SX, sy, SZ, GX, 173, GZ, FlightPlanner.Cfg.defaults(60), d3, srv[0], life, srv[1]);
                boolean walked = f.why().startsWith("walked: ") || f.why().startsWith("refused: ");
                if (walked) {
                    ends.merge(f.why().replaceAll("-?\\d+(\\.\\d+)?", "N"), 1, Integer::sum);
                    continue;
                }
                flown++;
                String u = FlightArcTest.unsafe(f, w.lastContacts, false);
                if (u == null && w.lastSpot) {
                    // a short landing: at rest on its spot, and nothing raised on the overrun past T
                    FlightPlanner.Profile pf = w.lastPlan.profile();
                    if (!pf.pad((int) Math.floor(f.restX()), (int) Math.floor(f.restZ()))) u = "at rest off the spot";
                    else if (FlightPlanner.overrunProblem(w.terrain(), pf) != null) u = "flew onto a pocket spot";
                }
                if (u != null) bad.add("life " + life + " delay " + srv[0] + ": " + u + " [" + f.why() + "]");
            }
        }
        System.out.printf("pocket leg: %d of 12 flown, %d unsafe%n", flown, bad.size());
        ends.forEach((k, n) -> System.out.println("  walked " + n + ": " + k));
        bad.forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.toString());
        assertFalse(flown == 0 && ends.isEmpty(), "every case ended somehow");
    }

    @Test
    void flyIntoWallDamageIsTheFlightsCollisionSignal() {
        DamageEvents.takeWall();
        DamageEvents.noteWall("minecraft:fall", 5);
        DamageEvents.noteWall(null, 6);
        assertTrue(DamageEvents.takeWall() < 0, "only fly_into_wall counts");
        DamageEvents.noteWall("minecraft:fly_into_wall", 7);
        assertTrue(DamageEvents.takeWall() == 7);
        assertTrue(DamageEvents.takeWall() < 0, "taken once");
        DamageEvents.noteWall("fly_into_wall", 8);
        assertTrue(DamageEvents.takeWall() == 8, "without the namespace too");
    }
}
