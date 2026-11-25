package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hovers (elytra stage 5, {@code build.elytraShortLandings}): legs shorter than a boost's reach
 * onto a small spot at the goal, the boost burnt over it, and the clear stand over the end rods (live: the art layer to
 * the clear stand flew an arc onto the tile and walked 648 ticks up to the stand, every map).
 */
class FlightHoverTest {
    static final int[][] SERVERS = {{2, 3}, {4, 3}, {8, 6}, {4, 20}};
    static final int[] LIVES = {40, 45, 51, 54, 60, 70, 85, 100};

    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
    }

    private record Out(String how, FlightSim.Flight f, boolean spot, boolean high, double y, int fast, int contacts,
                       int noGo, double offT, double moving, String burn, String spotWhy) {
        boolean walked() {
            return f.why().startsWith("walked: ") || f.why().startsWith("refused: ");
        }

        String unsafe() {
            if (walked()) return null;
            if (!f.why().equals("landed") && !f.why().startsWith("bailed")) return f.why();
            if (fast > 0) return "touched a block at speed";
            if (noGo > 0) return "in a no-go cell";
            if (f.wall()) return "ran into a wall";
            if (f.maxFall() > 1.0) return String.format("fell %.2f", f.maxFall());
            if (f.touchVy() <= -0.5) return String.format("sank %.2f at the touch-down", f.touchVy());
            if (moving >= 0.05) return String.format("still moving %.2f", moving);
            return null;
        }
    }

    private static Out fly(FlightSim w, String how, double[] leg, int worst, int life, int[] srv) {
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, worst);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        FlightSim.Flight f = w.fly(leg[0], leg[1], leg[2], (int) leg[3], (int) leg[4], (int) leg[5], pc, cc, srv[0], life,
            srv[1]);
        boolean flew = !f.why().startsWith("walked: ") && !f.why().startsWith("refused: ");
        return new Out(how, f, w.lastSpot, flew && w.lastPlan.profile().high, w.lastY, w.lastFastContacts,
            w.lastContacts, w.lastNoGo, w.lastOffT, w.lastMoving, w.lastBurn, String.valueOf(w.lastSpotWhy));
    }

    /** Starts around the stand (63.5, 63.5) at {@code d} blocks, 8 headings, on the art layer. */
    static List<double[]> standLegs(FlightSim w) {
        List<double[]> out = new ArrayList<>();
        for (int d : new int[]{31, 45, 60, 75}) {
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * 2 * k / 8 + 0.2;
                double x = Math.floor(63.5 + d * Math.cos(a)) + 0.5;
                double z = Math.floor(63.5 + d * Math.sin(a)) + 0.5;
                if (x < 1 || x > 126 || z < 1 || z > 126) continue;
                out.add(new double[]{x, feet(w, x, z), z, 63, 179, 63});
            }
        }
        return out;
    }

    @Test
    void theArtLayerToTheClearStandHoversOverTheEndRodsAndLandsOnTheGlass() {
        FlightSim w = new FlightSim().lights().stand().gated().spots(1);
        List<double[]> legs = standLegs(w);
        Map<String, Integer> walked = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        int cases = 0;
        int flown = 0;
        int onGlass = 0;
        double maxTouch = 0;
        double maxSink = 0;
        double maxOff = 0;
        int maxTicks = 0;
        for (double[] leg : legs) {
            for (int life : LIVES) {
                for (int[] srv : SERVERS) {
                    cases++;
                    String how = String.format("from %.0f,%.0f (%.0f blocks) life %d delay %d flag %d", leg[0], leg[2],
                        Math.hypot(leg[0] - 63.5, leg[2] - 63.5), life, srv[0], srv[1]);
                    Out o = fly(w, how, leg, 56, life, srv);
                    if (o.walked()) {
                        String why = o.spotWhy();
                        walked.merge(why.length() > 260 ? why.substring(0, 260) : why, 1, Integer::sum);
                        continue;
                    }
                    flown++;
                    String u = o.unsafe();
                    if (u != null) bad.add(how + ": " + u + " [" + o.f().why() + "] " + o.burn());
                    if (Math.abs(o.y() - 179.0) < 1e-6) onGlass++;
                    else bad.add(how + String.format(": at rest at y %.2f, %.1f off T [%s] %s", o.y(), o.offT(), o.f().why(), o.burn()));
                    maxTouch = Math.max(maxTouch, o.f().touchSpeed());
                    maxSink = Math.max(maxSink, -o.f().touchVy());
                    maxOff = Math.max(maxOff, o.offT());
                    maxTicks = Math.max(maxTicks, o.f().ticks());
                }
            }
        }
        System.out.printf("clear stand: %d cases, %d flown, %d on the glass; touch-down at most %.2f b/t, sink %.2f, %.2f off"
            + " T, %d ticks%n", cases, flown, onGlass, maxTouch, maxSink, maxOff, maxTicks);
        walked.forEach((k, n) -> System.out.println("  walked " + n + ": " + k));
        bad.stream().limit(40).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe or off the glass, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(flown >= cases / 2, flown + " of " + cases + " flown");
    }

    /** Short-leg goals: {name, x, y, z, first heading (degrees, 0 = +x, 90 = +z), heading step, headings}. */
    static final Object[][] GOALS = {
        {"drop point by the turnaround station", 6, 174, -2, 30, 30, 3},
        {"chest at the walkway's east end", 122, 174, -3, 100, 30, 3},
        {"walkway middle", 60, 174, -2, 45, 45, 3},
        {"lane start before the shaft", 20, 173, 117, -90, 45, 3},
        {"lane start before a no-go box", 40, 173, 110, -60, 60, 3},
        {"tile middle", 64, 173, 40, 0, 90, 4},
    };

    private record Short(String goal, double[] leg, boolean art, int life, int[] srv) { }

    /**
     * Legs of 31 to 59 blocks onto the walkway (the drop point by the turnaround station's no-go box, a chest at its east
     * end over the canal's end, its middle) and onto the tile (lane starts before the shaft and a no-go box, the
     * middle), bare and built art, boosts of 40 to 100 ticks with 56 planned for: every flight comes down on its spot at
     * the goal, slowly, touching nothing; the rest walk with the reason.
     */
    @Test
    void shortLegsHoverOntoASpotAtTheGoal() {
        List<Short> cases = new ArrayList<>();
        for (Object[] g : GOALS) {
            int gx = (int) g[1];
            int gy = (int) g[2];
            int gz = (int) g[3];
            for (int h = 0; h < (int) g[6]; h++) {
                double a = Math.toRadians((int) g[4] + h * (int) g[5]);
                for (int d : new int[]{31, 38, 45, 52, 59}) {
                    double x = Math.floor(gx + 0.5 + d * Math.cos(a)) + 0.5;
                    double z = Math.floor(gz + 0.5 + d * Math.sin(a)) + 0.5;
                    if (x < 1 || x > 126 || z < 1 || z > 126) continue;
                    for (boolean art : new boolean[]{false, true}) {
                        for (int life : new int[]{40, 51, 56, 70, 100}) {
                            for (int[] srv : new int[][]{{2, 3}, {4, 3}, {8, 6}}) {
                                cases.add(new Short((String) g[0], new double[]{x, 0, z, gx, gy, gz}, art, life, srv));
                            }
                        }
                    }
                }
            }
        }
        record R(Short c, Out o, boolean hover, double leg) { }
        List<R> out = cases.parallelStream().map(c -> {
            FlightSim w = FlightOverrunTest.world(c.art(), true);
            double[] leg = c.leg().clone();
            leg[1] = feet(w, leg[0], leg[2]);
            String how = String.format("%s from %.1f,%.1f (%.0f blocks)%s life %d delay %d flag %d", c.goal(), leg[0], leg[2],
                Math.hypot(leg[0] - leg[3] - 0.5, leg[2] - leg[5] - 0.5), c.art() ? " art" : " bare", c.life(), c.srv()[0],
                c.srv()[1]);
            Out o = fly(w, how, leg, 56, c.life(), c.srv());
            boolean hover = !o.walked() && w.lastPlan.profile().hover;
            return new R(c, o, hover, Math.hypot(leg[0] - leg[3] - 0.5, leg[2] - leg[5] - 0.5));
        }).toList();
        Map<String, int[]> byGoal = new TreeMap<>(); // cases, flown, hovers, on the spot, bailed out past the worst
        Map<String, Integer> walked = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        double maxTouch = 0;
        double maxSink = 0;
        double maxOff = 0;
        int maxTicks = 0;
        for (R r : out) {
            int[] n = byGoal.computeIfAbsent(r.c().goal(), k -> new int[5]);
            n[0]++;
            Out o = r.o();
            if (o.walked()) {
                String why = o.spotWhy().replaceAll("-?[0-9]+(\\.[0-9]+)?", "N");
                walked.merge(why.length() > 220 ? why.substring(0, 220) : why, 1, Integer::sum);
                continue;
            }
            n[1]++;
            if (r.hover()) n[2]++;
            String u = o.unsafe();
            if (u != null) bad.add(o.how() + ": " + u + " [" + o.f().why() + "] " + o.burn());
            if (o.spot() && o.f().why().equals("landed")) {
                if (o.offT() <= 1.0) n[3]++;
                else bad.add(o.how() + String.format(": at rest %.1f off T [%s] %s", o.offT(), o.f().why(), o.burn()));
                maxTouch = Math.max(maxTouch, o.f().touchSpeed());
                maxSink = Math.max(maxSink, -o.f().touchVy());
                maxOff = Math.max(maxOff, o.offT());
                maxTicks = Math.max(maxTicks, o.f().ticks());
            } else if (!o.spot()) {
                bad.add(o.how() + ": not a short landing [" + o.f().why() + "]");
            } else if (o.f().why().startsWith("bailed") && r.c().life() > 56) {
                n[4]++; // a boost past the longest planned for: the burn, then a safe bail-out may come down off the spot
            } else {
                bad.add(o.how() + ": " + o.f().why() + " " + o.burn());
            }
        }
        System.out.printf("short legs: %d cases; on the spot: touch-down at most %.2f b/t, sink %.2f, %.2f off T, %d ticks%n",
            out.size(), maxTouch, maxSink, maxOff, maxTicks);
        byGoal.forEach((k, n) -> System.out.printf("  %s: %d cases, %d flown (%d hovers), %d on the spot, %d bailed out"
            + " safely after a boost past the worst%n", k, n[0], n[1], n[2], n[3], n[4]));
        walked.forEach((k, n) -> System.out.println("  walked " + n + ": " + k));
        bad.stream().limit(40).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe or off the spot, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }
}
