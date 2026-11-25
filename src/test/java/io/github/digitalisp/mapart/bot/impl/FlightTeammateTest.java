package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teammates and the landing (elytra stage 5): a spot or pad with a player within
 * {@link FlightControl#PLAYER_MARGIN} is never planned; a player who walks onto the landing during the flight ends it in a
 * bail-out away from them before the touch-down's commit ({@link FlightControl#COMMIT_HEIGHT}).
 */
class FlightTeammateTest {
    /** Goals {x, y, z}: the tile's middle, the walkway's middle. */
    static final int[][] GOALS = {{64, 173, 40}, {60, 174, -2}};
    static final int[] LIVES = {40, 51, 56};
    static final int[][] SERVERS = {{2, 3}, {4, 3}, {8, 6}};
    /**
     * A teammate who comes under the bot this low over the floor (blocks) is too late: sinking at 0.15 b/t and drifting
     * at most 0.4 b/t aside (slow over T: its lift adds about 0.017 b/t a tick), the bot cannot get 2.5 blocks away
     * before it is down.
     */
    static final double LATE = 3.0;

    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
    }

    /** Starts on the tile {@code d} blocks from goal {@code g}, 5 headings south of it. */
    static List<double[]> starts(FlightSim w, int[] g) {
        List<double[]> out = new ArrayList<>();
        for (int d : new int[]{35, 45, 55, 70, 90}) {
            for (int k = 0; k < 5; k++) {
                double a = Math.toRadians(30 + 30 * k);
                double x = Math.floor(g[0] + 0.5 + d * Math.cos(a)) + 0.5;
                double z = Math.floor(g[2] + 0.5 + d * Math.sin(a)) + 0.5;
                if (x < 1 || x > 126 || z < 1 || z > 126) continue;
                out.add(new double[]{x, feet(w, x, z), z});
            }
        }
        return out;
    }

    private static String unsafe(FlightSim w, FlightSim.Flight f) {
        if (!f.why().equals("landed") && !f.why().startsWith("bailed")) return f.why();
        if (w.lastFastContacts > 0) return "touched a block at speed";
        if (w.lastNoGo > 0) return "in a no-go cell";
        if (f.wall()) return "ran into a wall";
        if (f.maxFall() > 1.0) return String.format("fell %.2f", f.maxFall());
        if (f.touchVy() <= -0.5) return String.format("sank %.2f at the touch-down", f.touchVy());
        if (w.lastMoving >= 0.05) return String.format("still moving %.2f", w.lastMoving);
        return null;
    }

    private static FlightLeg.Landing plan(FlightSim w, double[] s, int[] g) {
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        return FlightLeg.withSpot(s[0], s[1], s[2], g[0], g[1], g[2], 1, w.terrain(), pc, cc, 30, true, 60, 3);
    }

    /**
     * A teammate standing on the goal: no spot cell within the margin of them is planned; a flight that goes comes to
     * rest away from them, touching nothing.
     */
    @Test
    void aTeammateOnTheGoalIsNeverLandedOn() {
        int cases = 0;
        int flown = 0;
        double nearest = Double.MAX_VALUE;
        List<String> bad = new ArrayList<>();
        for (int[] g : GOALS) {
            for (boolean art : new boolean[]{false, true}) {
                FlightSim w = FlightOverrunTest.world(art, true);
                double[] mate = {g[0] + 0.5, g[1], g[2] + 0.5};
                w.players.add(mate);
                for (double[] s : starts(w, g)) {
                    FlightLeg.Landing l = plan(w, s, g);
                    if (l.result().plan() == null) {
                        cases += LIVES.length * SERVERS.length;
                        continue;
                    }
                    FlightPlanner.Profile p = l.result().plan().profile();
                    for (long key : p.pad) {
                        double d = FlightControl.cellDist(mate[0], mate[2], (int) (key >> 32), (int) key);
                        if (d < FlightControl.PLAYER_MARGIN) bad.add(String.format("a pad cell %.1f from the teammate", d));
                    }
                    for (int life : LIVES) {
                        for (int[] srv : SERVERS) {
                            cases++;
                            FlightSim.Flight f = w.flyPlan(l.result().plan(), s[0], s[1], s[2],
                                FlightControl.Cfg.forDuration(3, 3, 1, 56).withRockets(l.relay() ? 2 : 1), srv[0], life, srv[1]);
                            flown++;
                            String how = String.format("goal %d,%d from %.0f,%.0f%s life %d", g[0], g[2], s[0], s[2],
                                art ? " art" : " bare", life);
                            String u = unsafe(w, f);
                            if (u != null) bad.add(how + ": " + u);
                            double d = Math.hypot(f.restX() - mate[0], f.restZ() - mate[2]);
                            nearest = Math.min(nearest, d);
                            if (d < FlightControl.PLAYER_MARGIN) bad.add(how + String.format(": at rest %.1f from the teammate", d));
                        }
                    }
                }
            }
        }
        System.out.printf("a teammate on the goal: %d cases, %d flown, at rest at least %.1f from them%n", cases, flown, nearest);
        bad.stream().limit(20).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " bad, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(flown > 0, "some legs fly onto another spot");
    }

    /**
     * A teammate walks onto the landing's T during the flight (from flight tick 10, 30, 50 or 70): the flight gives the
     * landing up (the brake onto a place beside them, else a bail-out away from them), safely, touching nothing; it
     * comes to rest within the margin of them only when they came too late for that ({@link #LATE}), or, rarely, when
     * the landing was committed with no place beside it (a player is no block: the planned landing is the safe one).
     */
    @Test
    void aTeammateWalkingOntoTheLandingInFlightEndsItInABailOut() {
        record Case(int[] g, boolean art, double[] s, int life, int[] srv, int from) { }
        List<Case> cases = new ArrayList<>();
        for (int[] g : GOALS) {
            for (boolean art : new boolean[]{false, true}) {
                FlightSim w0 = FlightOverrunTest.world(art, true);
                for (double[] s : starts(w0, g)) {
                    for (int life : LIVES) {
                        for (int[] srv : SERVERS) {
                            for (int from : new int[]{10, 30, 50, 70}) cases.add(new Case(g, art, s, life, srv, from));
                        }
                    }
                }
            }
        }
        record R(Case c, String kind, String bad) { }
        List<R> out = cases.parallelStream().map(c -> {
            FlightSim w = FlightOverrunTest.world(c.art(), true);
            FlightLeg.Landing l = plan(w, c.s(), c.g());
            if (l.result().plan() == null) return new R(c, "walked", null);
            FlightPlanner.Profile p = l.result().plan().profile();
            double[] mate = {p.restX, p.level, p.restZ};
            w.players.add(mate);
            w.playersFrom = c.from();
            FlightSim.Flight f = w.flyPlan(l.result().plan(), c.s()[0], c.s()[1], c.s()[2],
                FlightControl.Cfg.forDuration(3, 3, 1, 56).withRockets(l.relay() ? 2 : 1), c.srv()[0], c.life(), c.srv()[1]);
            String how = String.format("goal %d,%d from %.0f,%.0f%s life %d from tick %d (flight %d ticks)", c.g()[0],
                c.g()[2], c.s()[0], c.s()[2], c.art() ? " art" : " bare", c.life(), c.from(), f.ticks());
            how += String.format(" (the teammate came %.1f over the floor at %.2f b/t)", w.lastHeightAtPlayers,
                w.lastSpeedAtPlayers);
            String u = unsafe(w, f);
            if (u != null) return new R(c, "unsafe", how + ": " + u + " [" + w.lastPlayerWhy + "]");
            double d = Math.hypot(f.restX() - mate[0], f.restZ() - mate[2]);
            if (c.from() >= f.ticks() || Double.isNaN(w.lastHeightAtPlayers) || w.lastHeightAtPlayers == 0) {
                return new R(c, "down before the teammate came", null);
            }
            String gave = w.lastPlayerWhy == null ? "kept the landing" : f.why().equals("landed") ? "landed beside them"
                : "bailed out";
            if (d >= FlightControl.PLAYER_MARGIN) return new R(c, gave + ", away from the teammate", null);
            if (w.lastHeightAtPlayers < LATE) return new R(c, gave + " near them: they came under " + LATE + " over the floor", null);
            if (w.lastPlayerWhy == null) {
                // committed: no place beside the landing with room for a slow touch-down, and too low and fast for a
                // bail-out among blocks; a player is no block (FlightControl.mayDodge)
                return new R(c, "kept the landing near them, committed", null);
            }
            return new R(c, "near", how + String.format(": %s, at rest %.1f from the teammate", gave, d));
        }).toList();
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        for (R r : out) {
            kinds.merge(r.kind(), 1, Integer::sum);
            if (r.bad() != null) bad.add(r.bad());
        }
        System.out.println("a teammate onto the landing in flight: " + out.size() + " cases " + kinds);
        bad.stream().filter(x -> !x.contains("from the teammate")).limit(15).forEach(x -> System.out.println("  UNSAFE " + x));
        Map<String, Integer> badKinds = new TreeMap<>();
        for (String x : bad) badKinds.merge(x.substring(x.lastIndexOf("): ") + 3).replaceAll("-?[0-9]+(\\.[0-9]+)?", "N"), 1, Integer::sum);
        badKinds.forEach((k, n) -> System.out.println("  bad " + n + ": " + k));
        bad.stream().limit(20).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " bad, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(kinds.getOrDefault("landed beside them, away from the teammate", 0) > 0, "some flights land beside them");
        int flown = out.size() - kinds.getOrDefault("walked", 0) - kinds.getOrDefault("down before the teammate came", 0);
        int kept = kinds.getOrDefault("kept the landing near them, committed", 0);
        assertTrue(kept * 50 <= flown, "a landing kept beside a teammate in at most 2 % of the flights: " + kept + " of " + flown);
    }
}
