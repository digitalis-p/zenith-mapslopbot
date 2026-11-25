package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boosts past the longest planned for, and the slide after the touch-down (elytra stage 5). Live
 * 2026-10-25 08:47: a straight DROP_POINT flight of 141 blocks planned for boosts of up to 54 ticks; the server
 * froze, the boost ran 85, the bot touched down at 1.69 b/t with the rocket still pushing and slid on toward a no-go
 * zone. Here: legs that end near no-go zones, a shaft through the floor and the walkway's end over the canal, with
 * boosts of 40 to 100 ticks, round trips of 2 to 8 ticks, the server's answer to the touch-down 3 to 20 ticks late, a
 * TPS dip in flight and a silent server. Every flight that takes off must come down touching no block faster than 0.3
 * b/t, never in a no-go cell, water or a fall, sinking under 0.5 b/t, and stand still at its end.
 */
class FlightOverrunTest {
    private static final int[][] SERVERS = {{2, 3}, {4, 3}, {8, 6}, {4, 10}, {4, 20}};
    /**
     * The longest slide after a short landing's touch-down (blocks; the server's answer 20 ticks late slides a slow
     * touch-down on 2.4); a long pad's is its run-out past R.
     */
    static final double SPOT_SLIDE = 3;

    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
    }

    /**
     * The soul sand model under the end rods with what lies past the legs' ends: the turnaround station's no-go box at
     * the walkway's west end (by the drop point), a shaft through the floor south of a lane start near the tile's south
     * edge (the stasis chunk loaders), a no-go box (a machine) past another lane start, the canal and the void past the
     * tile's west edge and the walkway's east end. {@code spots}: short landings first ({@code build.elytraShortLandings}).
     */
    static FlightSim world(boolean art, boolean spots) {
        FlightSim w = new FlightSim().lights().gated();
        if (art) w.art(4, 2);
        if (spots) w.spots(1);
        w.noGo(0, 174, -4, 3, 177, -1);
        w.shaft(18, 122, 22, 126);
        w.noGo(35, 173, 114, 41, 177, 118);
        return w;
    }

    static final String[] KINDS = {"drop point west", "tile west edge", "chest east end", "lane start before a shaft",
        "lane start before a no-go box", "walkway to the art"};

    /** Leg kind {@code k}: from feet {x, y, z} to goal cell {x, y, z}; each leg's line runs on past its goal into its hazard. */
    static double[] leg(FlightSim w, int k) {
        return switch (k) {
            case 0 -> new double[]{100.5, feet(w, 100.5, 100.5), 100.5, 6, 174, -2}; // 141 blocks, as live
            case 1 -> new double[]{124.5, feet(w, 124.5, 110.5), 110.5, 2, 173, 30}; // 146
            case 2 -> new double[]{10.5, feet(w, 10.5, 80.5), 80.5, 122, 174, -3}; // 139
            case 3 -> new double[]{100.5, feet(w, 100.5, 10.5), 10.5, 20, 173, 117}; // 133
            case 4 -> new double[]{124.5, feet(w, 124.5, 2.5), 2.5, 40, 173, 110}; // 136
            default -> new double[]{100.5, 174, -2.5, 20, 173, 100}; // 130
        };
    }

    private record Case(int kind, boolean art, boolean spots, int worst, int life, int[] srv, int mode) {
        String how() {
            return KINDS[kind] + (art ? " art" : " bare") + (spots ? " spot" : " pad") + " worst " + worst + " life " + life
                + " delay " + srv[0] + " flag " + srv[1] + (mode == 1 ? " silent" : mode == 2 ? " tps dip" : "");
        }
    }

    private record Out(Case c, FlightSim.Flight f, int noGo, int fast, boolean wet, double groundSpeed, String burn,
                       int groundBoost, double moving, double offR, boolean spot, double slide) {
        boolean walked() {
            return f.why().startsWith("walked: ") || f.why().startsWith("refused: ");
        }

        boolean bailed() {
            return f.why().startsWith("bailed: ");
        }
    }

    private static Out run(Case c) {
        FlightSim w = world(c.art(), c.spots());
        double[] l = leg(w, c.kind());
        if (c.mode() == 1) {
            // the server silent from 20 ticks into the boost to 60 after it: the rocket's end and the glide's cannot come
            w.silentFrom = 30;
            w.silentTo = 30 + c.life();
        }
        if (c.mode() == 2) {
            w.raiseAt = 25;
            w.raiseTo = 66;
        }
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, c.worst());
        FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5], FlightPlanner.Cfg.defaults(60), cc,
            c.srv()[0], c.life(), c.srv()[1]);
        return new Out(c, f, w.lastNoGo, w.lastFastContacts, w.lastWet, w.lastGroundSpeed, w.lastBurn, w.lastGroundBoost,
            w.lastMoving, w.lastOffT, w.lastSpot, Double.isNaN(w.lastTouchX) ? 0
                : Math.hypot(f.restX() - w.lastTouchX, f.restZ() - w.lastTouchZ));
    }

    /** Why a flown case breaks the rules (null: it keeps them). */
    static String unsafe(FlightSim.Flight f, int noGo, int fast, boolean wet, double moving) {
        if (f.why().equals("timeout")) return "timeout";
        if (f.why().startsWith("abort: ")) return f.why();
        if (noGo > 0) return "in a no-go cell " + noGo + " ticks";
        if (fast > 0) return "touched a block faster than 0.3 b/t " + fast + " times";
        if (f.wall()) return "ran into a wall";
        if (wet) return "came down in water";
        if (f.maxFall() > 1.0 + 1e-9) return "fall distance " + f.maxFall();
        if (f.touchVy() <= -0.5) return "sink " + f.touchVy() + " at the touch-down";
        if (moving >= 0.05) return "still moving " + moving + " b/t at the end";
        return null;
    }

    /**
     * The sweep: every kind, bare tile and built art, planned for 54 or 56 ticks, boosts of 40 to 100 ticks, five
     * servers (round trips 2-8, the touch-down's answer 3-20 ticks late), and with boosts over the worst a silent server
     * and a TPS dip in flight. Each flight keeps the rules; a boost past the worst burns (cruise on, spin in place) and
     * never comes down under the boost.
     */
    @Test
    void boostsPastTheWorstPlannedForBurnAndLandSafely() {
        List<Case> cases = new ArrayList<>();
        for (int k = 0; k < KINDS.length; k++) {
            for (boolean art : new boolean[]{false, true}) {
                for (boolean spots : new boolean[]{false, true}) {
                    for (int worst : new int[]{54, 56}) {
                        for (int life : new int[]{40, 51, 60, 70, 85, 100}) {
                            for (int[] srv : SERVERS) {
                                cases.add(new Case(k, art, spots, worst, life, srv, 0));
                                if (life >= 70 && srv[0] == 4 && srv[1] == 3) {
                                    cases.add(new Case(k, art, spots, worst, life, srv, 1));
                                    cases.add(new Case(k, art, spots, worst, life, srv, 2));
                                }
                            }
                        }
                    }
                }
            }
        }
        List<Out> out = cases.parallelStream().map(FlightOverrunTest::run).toList();
        Map<Integer, int[]> byLife = new TreeMap<>(); // cases, flown, burnt, on the ground under the boost, bailed, spot
        Map<Integer, double[]> numbers = new TreeMap<>(); // most ground speed, most off R, sum off R
        List<String> bad = new ArrayList<>();
        Map<String, int[]> byKind = new TreeMap<>();
        Map<String, Integer> bails = new TreeMap<>();
        Map<String, Integer> bailWhere = new TreeMap<>();
        Map<String, String> walkedWhy = new TreeMap<>();
        List<String> odd = new ArrayList<>();
        for (Out o : out) {
            int[] n = byLife.computeIfAbsent(o.c().life(), x -> new int[6]);
            double[] m = numbers.computeIfAbsent(o.c().life(), x -> new double[4]);
            int[] kk = byKind.computeIfAbsent(KINDS[o.c().kind()], x -> new int[2]);
            n[0]++;
            kk[1]++;
            if (o.walked()) {
                walkedWhy.putIfAbsent(KINDS[o.c().kind()] + (o.c().art() ? " art" : " bare") + (o.c().spots() ? " spot" : " pad"),
                    o.f().why());
                continue;
            }
            if (o.groundBoost() > 0 || o.offR() > 20) odd.add(o.c().how() + ": " + o.f().why() + "; " + o.burn()
                + "; ground boost " + o.groundBoost() + ", off R " + String.format("%.1f", o.offR()));
            n[1]++;
            kk[0]++;
            if (!o.burn().isEmpty()) n[2]++;
            if (o.groundBoost() > 0) n[3]++;
            if (o.spot()) n[5]++;
            if (o.bailed()) {
                n[4]++;
                bailWhere.merge(KINDS[o.c().kind()] + (o.c().art() ? " art" : " bare") + (o.c().spots() ? " spot" : " pad")
                    + " life " + o.c().life(), 1, Integer::sum);
                bails.merge(o.f().why().replaceAll("[-0-9.]+", "N"), 1, Integer::sum);
            }
            m[0] = Math.max(m[0], o.groundSpeed());
            m[1] = Math.max(m[1], o.offR());
            m[2] += o.offR();
            String why = unsafe(o.f(), o.noGo(), o.fast(), o.wet(), o.moving());
            if (why == null && o.c().life() > o.c().worst() + 2 && o.burn().isEmpty() && o.groundBoost() == 0
                && o.c().mode() == 0) {
                why = "a boost of " + o.c().life() + " past the " + o.c().worst() + " planned for neither burned nor came"
                    + " down under it";
            }
            if (why == null && o.slide() > (o.spot() && !o.bailed() ? SPOT_SLIDE : FlightPlanner.RUN_OUT)) {
                why = String.format("slid %.1f blocks after the touch-down", o.slide());
            }
            m[3] = Math.max(m[3], o.slide());
            if (why != null) bad.add(o.c().how() + ": " + why + " (" + o.f().why() + ")");
        }
        StringBuilder sb = new StringBuilder(String.format("overrun sweep: %d cases, %d unsafe%n", out.size(), bad.size()));
        byLife.forEach((life, n) -> {
            double[] m = numbers.get(life);
            sb.append(String.format("  boost %3d: %d cases, %d flown (%d onto a spot), %d burned, %d on the ground under the"
                + " boost, %d bailed out; fastest slide tick %.2f, longest slide %.1f, rest off R at most %.1f (mean %.1f)%n",
                life, n[0], n[1], n[5], n[2], n[3], n[4], m[0], m[3], m[1], n[1] == 0 ? 0 : m[2] / n[1]));
        });
        byKind.forEach((k, v) -> sb.append(String.format("  %s: %d of %d flown%n", k, v[0], v[1])));
        bails.forEach((k, v) -> sb.append(String.format("  bail-outs: %d x %s%n", v, k)));
        bailWhere.forEach((k, v) -> sb.append(String.format("  bailed out: %d x %s%n", v, k)));
        walkedWhy.forEach((k, v) -> sb.append(String.format("  walked, %s: %s%n", k, v)));
        odd.forEach(x -> sb.append("  odd: ").append(x).append('\n'));
        System.out.print(sb);
        bad.stream().limit(40).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(byLife.get(85)[1] > 0 && byLife.get(100)[1] > 0, "long boosts were flown");
    }

    /**
     * The live case: 141 blocks to the drop point beside the turnaround station's no-go box, boosts of 54 planned for,
     * 85 boosted: the flight burns (on toward the landing, then a spin in place), lands after the boost and stands still
     * outside the box.
     */
    @Test
    void theLiveDropPointCaseBurnsAndStopsOutsideTheNoGoBox() {
        FlightSim w = world(false, false);
        double[] l = leg(w, 0);
        w.trace = new StringBuilder();
        FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5], FlightPlanner.Cfg.defaults(60),
            FlightControl.Cfg.forDuration(3, 3, 1, 54), 4, 85, 6);
        System.out.println("live drop point case: " + f + "; " + w.lastBurn + "; off R " + w.lastOffT);
        assertFalse(f.why().startsWith("walked") || f.why().startsWith("refused"), f.why());
        assertNull(unsafe(f, w.lastNoGo, w.lastFastContacts, w.lastWet, w.lastMoving), w.trace.toString());
        assertFalse(w.lastBurn.isEmpty(), "burned");
        assertEquals(0, w.lastGroundBoost, "never on the ground under the boost");
    }

    /** On the ground with a rocket still boosting the look goes straight down: the slide stops within a few blocks. */
    @Test
    void aBoostOnTheGroundLooksDownAndStopsTheSlide() {
        FlightSim w = new FlightSim().open();
        FlightPlanner.Result r = FlightPlanner.plan(300.5, 173, 300.5, 300, 173, 420, w.terrain(), FlightPlanner.Cfg.defaults(60));
        assertNotNull(r.plan(), r.refusal());
        FlightControl fc = new FlightControl(r.plan().profile(), FlightControl.Cfg.forDuration(3, 3, 1, 54), 0f);
        fc.touchedDown();
        // touched down at 1.6 b/t with the rocket on and the glide flag still set
        FlightSim.Bot b = new FlightSim.Bot(w, 300.5, 173, 380.5);
        b.vz = 1.6;
        b.fallFlying = true;
        b.onGround = true;
        b.boostFrom = 0;
        b.boostTo = 40;
        b.flagDelay = 30;
        double z0 = b.z;
        for (int i = 0; i < 60; i++) b.step(fc.tick(b.in()));
        System.out.printf("boost on the ground: slid %.2f blocks, speed %.3f, pitch %.0f%n", b.z - z0, Math.hypot(b.vx, b.vz),
            b.pitch);
        assertTrue(b.z - z0 < 4.5, "slid " + (b.z - z0));
        assertTrue(Math.hypot(b.vx, b.vz) < 0.05);
    }
}
