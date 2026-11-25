package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long hops (elytra stage 5, {@code build.elytraLongHops}): a leg whose goal is not loaded yet
 * flies to a stop on a spot in the loaded part along the way ({@link FlightLeg#withLongHop}), then on from there once the
 * goal has loaded (live: far chests 268 blocks and the store's 318 walked "not loaded" every time).
 */
class FlightLongHopTest {
    /** The walkway's east end chest (122, 174, -3): its part of the world from x {@code edge} on not loaded. */
    private static FlightSim world(boolean art, int edge) {
        FlightSim w = FlightOverrunTest.world(art, true);
        for (int x = edge; x <= 140; x++) {
            for (int z = -12; z <= 140; z++) w.unloaded.add(x + "," + z);
        }
        return w;
    }

    /**
     * The walkway's middle (60, 174, -2): the rows from z {@code edge} north not loaded (the tile's south half to the
     * walkway, 100-127 blocks).
     */
    private static FlightSim north(boolean art, int edge) {
        FlightSim w = FlightOverrunTest.world(art, true);
        for (int x = -12; x <= 140; x++) {
            for (int z = -12; z <= edge; z++) w.unloaded.add(x + "," + z);
        }
        return w;
    }

    /** The goals: {x, y, z}, the world for each ({@link #world} from x 95, {@link #north} to z 30). */
    static final int[][] GOALS = {{122, 174, -3}, {60, 174, -2}};

    /** {@link SimRoute}'s box margin for the no-go strip's way round (its gap 52 blocks off the line). */
    private static final int WIDE = 70;

    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
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

    /**
     * From the tile's south half toward the chest at the walkway's east end (139-175 blocks; the world east of x 95 not
     * loaded) and toward the walkway's middle (100-127 blocks; the rows north of z 30 not loaded): each flight lands on
     * a stop's spot in the loaded part (none of it in the part not loaded, nothing touched, slowly), well on toward the
     * goal; from where it came to rest, the world loaded now, a second flight onto a spot at the goal where one passes
     * (the chest at the east end: mostly not, a drop over 8 beside it), safely.
     */
    @Test
    void aGoalNotLoadedFliesToAStopAlongTheWayThenOnToTheGoal() {
        record Case(int goal, double x, double z, boolean art, int life, int[] srv) { }
        List<Case> cases = new ArrayList<>();
        double[][][] starts = {
            {{5.5, 120.5}, {20.5, 100.5}, {40.5, 124.5}, {60.5, 110.5}, {8.5, 80.5}, {30.5, 75.5}},
            {{10.5, 110.5}, {60.5, 124.5}, {90.5, 100.5}, {120.5, 118.5}, {30.5, 96.5}, {75.5, 106.5}},
        };
        for (int k = 0; k < GOALS.length; k++) {
            for (double[] s : starts[k]) {
                for (boolean art : new boolean[]{false, true}) {
                    for (int life : new int[]{40, 51, 56, 70, 100}) {
                        for (int[] srv : new int[][]{{2, 3}, {4, 3}, {8, 6}}) cases.add(new Case(k, s[0], s[1], art, life, srv));
                    }
                }
            }
        }
        record R(Case c, String how, String walked, String bad, double gained, double second, String secondWhy,
                 boolean onTop, long ms) { }
        List<R> out = cases.parallelStream().map(c -> {
            FlightSim w = c.goal() == 0 ? world(c.art(), 95) : north(c.art(), 30);
            int[] g0 = GOALS[c.goal()];
            FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
            FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
            double sy = feet(w, c.x(), c.z());
            String how = String.format("goal %d from %.1f,%.1f%s life %d delay %d flag %d", c.goal(), c.x(), c.z(),
                c.art() ? " art" : " bare", c.life(), c.srv()[0], c.srv()[1]);
            long t0 = System.nanoTime();
            FlightLeg.Hop h = FlightLeg.withLongHop(c.x(), sy, c.z(), g0[0], g0[1], g0[2], w.terrain(), pc, cc, 30, 3,
                SimRoute.route(w.terrain(), c.x(), sy, c.z(), g0[0], g0[1], g0[2]));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (h.landing().result().plan() == null) {
                return new R(c, how, String.format("goal %d from %.0f,%.0f: ", c.goal(), c.x(), c.z()) + h.landing().result().refusal(), null, 0,
                    0, null, false, ms);
            }
            FlightPlanner.Plan p = h.landing().result().plan();
            for (long key : p.profile().pad) {
                int px = (int) (key >> 32);
                int pz = (int) key;
                if (w.unloaded.contains(px + "," + pz)) return new R(c, how, null, "a stop's spot not loaded", 0, 0, null, false, ms);
            }
            FlightSim.Flight f = w.flyPlan(p, c.x(), sy, c.z(), cc.withRockets(h.landing().relay() ? 2 : 1), c.srv()[0],
                c.life(), c.srv()[1]);
            String u = unsafe(w, f);
            if (u != null) return new R(c, how, null, u + " [" + f.why() + "] " + w.lastBurn, 0, 0, null, false, ms);
            double before = Math.hypot(g0[0] + 0.5 - c.x(), g0[2] + 0.5 - c.z());
            double after = Math.hypot(g0[0] + 0.5 - f.restX(), g0[2] + 0.5 - f.restZ());
            if (!f.why().equals("landed")) {
                // a boost past the longest planned for: the burn and a safe bail-out may come down off the stop
                return new R(c, how, null, c.life() > 56 ? null : "bailed: " + f.why(), before - after, 0, "bailed", false, ms);
            }
            boolean onSpot = w.lastOffT <= 1.0 || p.profile().pad((int) Math.floor(f.restX()), (int) Math.floor(f.restZ()));
            if (!onSpot && c.life() <= 56) {
                return new R(c, how, null, String.format("at rest %.1f off the stop's T", w.lastOffT), before - after, 0, null,
                    false, ms);
            }
            // the goal loaded now: on from where it came to rest
            w.unloaded.clear();
            double fy = feet(w, f.restX(), f.restZ());
            FlightSim.Flight g = w.fly(f.restX(), fy, f.restZ(), g0[0], g0[1], g0[2], pc, cc, c.srv()[0], Math.min(c.life(), 56),
                c.srv()[1]);
            boolean flew = !g.why().startsWith("walked: ") && !g.why().startsWith("refused: ");
            String u2 = flew ? unsafe(w, g) : null;
            if (u2 != null) return new R(c, how, null, "second flight: " + u2 + " [" + g.why() + "]", before - after, 0, null, false, ms);
            double end = flew ? Math.hypot(g0[0] + 0.5 - g.restX(), g0[2] + 0.5 - g.restZ()) : after;
            String sw = "goal " + c.goal() + ": " + (flew ? g.why() : (g.why() + "; spot: " + w.lastSpotWhy)
                .replaceAll("-?[0-9]+(\\.[0-9]+)?", "N"));
            return new R(c, how, null, null, before - after, end, sw, onSpot, ms);
        }).toList();
        int flown = 0;
        int onStop = 0;
        int second = 0;
        double minGain = Double.MAX_VALUE;
        double maxEnd = 0;
        long maxMs = 0;
        Map<String, Integer> walked = new TreeMap<>();
        Map<String, Integer> seconds = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        for (R r : out) {
            maxMs = Math.max(maxMs, r.ms());
            if (r.walked() != null) {
                walked.merge(r.walked().substring(0, r.walked().indexOf(": ") + 2)
                    + r.walked().substring(r.walked().indexOf(": ") + 2).replaceAll("-?[0-9]+(\\.[0-9]+)?", "N"), 1, Integer::sum);
                continue;
            }
            flown++;
            if (r.bad() != null) {
                bad.add(r.how() + ": " + r.bad());
                continue;
            }
            if (r.onTop()) onStop++;
            minGain = Math.min(minGain, r.gained());
            if (r.secondWhy() != null) seconds.merge(r.secondWhy().length() > 400 ? r.secondWhy().substring(0, 400)
                : r.secondWhy(), 1, Integer::sum);
            if (r.secondWhy() != null && r.secondWhy().endsWith(": landed")) {
                second++;
                maxEnd = Math.max(maxEnd, r.second());
            }
        }
        System.out.printf("long hops: %d cases, %d flown to a stop, %d on the stop's spot, gained at least %.0f blocks; %d"
            + " second flights landed, at most %.1f from the goal; planning at most %d ms%n", out.size(), flown, onStop,
            minGain, second, maxEnd, maxMs);
        walked.forEach((k, n) -> System.out.println("  walked " + n + ": " + k));
        seconds.forEach((k, n) -> System.out.println("  second " + n + ": " + k));
        bad.stream().limit(30).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(flown >= out.size() / 2, "most legs fly to a stop: " + flown + " of " + out.size());
        assertTrue(minGain >= 50, "each stop well on toward the goal: " + minGain);
    }

    /** The planning time of a long hop, one at a time (the bot's tick waits for it): each start, bare art. */
    @Test
    void aLongHopPlansInReasonableTime() {
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        FlightSim w = world(false, 95);
        long[] ms = new long[6];
        int i = 0;
        FlightLeg.withLongHop(20.5, 173, 100.5, 122, 174, -3, w.terrain(), pc, cc, 30, 3,
            SimRoute.route(w.terrain(), 20.5, 173, 100.5, 122, 174, -3)); // warm up
        for (double[] s : new double[][]{{5.5, 120.5}, {20.5, 100.5}, {40.5, 124.5}, {60.5, 110.5}, {8.5, 80.5}, {30.5, 75.5}}) {
            List<io.github.digitalisp.mapart.model.Pos> r = SimRoute.route(w.terrain(), s[0], feet(w, s[0], s[1]), s[1], 122, 174, -3);
            long t0 = System.nanoTime();
            FlightLeg.withLongHop(s[0], feet(w, s[0], s[1]), s[1], 122, 174, -3, w.terrain(), pc, cc, 30, 3, r);
            ms[i++] = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.println("long hop planning, one at a time (ms): " + java.util.Arrays.toString(ms));
        for (long m : ms) assertTrue(m < 1500, "a long hop planned in " + m + " ms");
    }

    /** build.elytraLongHops: at least {@link FlightLeg#LONG_HOPS} further flights a leg; off: build.elytraHops as set. */
    @Test
    void longHopsAllowFurtherFlights() {
        var b = new io.github.digitalisp.mapart.config.MapArtConfig.BuildCfg();
        assertTrue(FlightLeg.hopsAllowed(b) == 0, "off by default: one flight a leg");
        b.elytraLongHops = true;
        assertTrue(FlightLeg.hopsAllowed(b) == FlightLeg.LONG_HOPS);
        b.elytraHops = 6;
        assertTrue(FlightLeg.hopsAllowed(b) == 6);
        b.elytraLongHops = false;
        b.elytraHops = 1;
        assertTrue(FlightLeg.hopsAllowed(b) == 1);
    }

    /**
     * A leg as long as the map station's to the cartography table (live 2026-10-25: 269 blocks walked in 633 ticks every
     * map, "no landing pad near the goal (not loaded ...)") or the store's to the platform (318): an open field, the
     * server's chunks within a view distance of 6 or 8 around the bot. Each flight from where the last one came to
     * rest: a long hop to a stop while the goal is not loaded, then onto a spot at the goal; the rest walks. Every
     * flight safe; the leg's time estimated as the flights' ticks, 20 a flight to settle and take off, and the walk
     * left at the live 2.35 ticks a block, against the walk of the whole leg.
     */
    @Test
    void aLongLegFliesInHopsAsItsChunksLoad() {
        hopLegs("long legs in hops", false);
    }

    /**
     * {@link #aLongLegFliesInHopsAsItsChunksLoad} with no floor on the line itself from 40 blocks on (a 1-wide shaft
     * along it: local 10-25, 11 of the LOCK leg's plans found no stop with a standing floor on the line): the stops
     * lie beside it ({@link FlightLeg#HOP_ASIDE}), every flight safe.
     */
    @Test
    void stopsBesideTheLineWhenTheLineHasNoFloor() {
        hopLegs("long legs in hops, no floor on the line", true);
    }

    private static void hopLegs(String name, boolean noFloorOnLine) {
        record Case(double leg, int heading, int vd, int life, int[] srv) { }
        List<Case> cases = new ArrayList<>();
        for (double leg : new double[]{200, 269, 318}) {
            for (int heading : new int[]{0, 37, 90, 151}) {
                for (int vd : new int[]{6, 8}) {
                    for (int life : new int[]{40, 51, 56}) {
                        for (int[] srv : new int[][]{{2, 3}, {4, 3}, {8, 6}}) cases.add(new Case(leg, heading, vd, life, srv));
                    }
                }
            }
        }
        record R(Case c, int flights, int air, double left, String end, String bad) { }
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        List<R> out = cases.parallelStream().map(c -> {
            FlightSim w = new FlightSim().open().gated().spots(1);
            w.loadChunks = c.vd();
            double x = 8.5;
            double z = 8.5;
            int gx = (int) Math.floor(x + Math.cos(Math.toRadians(c.heading())) * c.leg());
            int gz = (int) Math.floor(z + Math.sin(Math.toRadians(c.heading())) * c.leg());
            int gy = 173;
            if (noFloorOnLine) {
                double ux = Math.cos(Math.toRadians(c.heading()));
                double uz = Math.sin(Math.toRadians(c.heading()));
                for (double s = 40; s < c.leg() - 20; s += 0.25) w.holes.add((int) Math.floor(x + ux * s) + "," + (int) Math.floor(z + uz * s));
            }
            int flights = 0;
            int air = 0;
            String end = "flights used up";
            while (flights <= FlightLeg.LONG_HOPS) {
                w.loadX = x;
                w.loadZ = z;
                if (Math.hypot(gx + 0.5 - x, gz + 0.5 - z) < 30) {
                    end = "at the goal";
                    break;
                }
                double sy = w.floorAt(x, 174.0, z);
                FlightSim.Flight f;
                if (!w.terrain().loaded(gx, gz)) {
                    FlightLeg.Hop h = FlightLeg.withLongHop(x, sy, z, gx, gy, gz, w.terrain(), pc, cc, 30, 3,
                        SimRoute.route(w.terrain(), x, sy, z, gx, gy, gz));
                    if (h.landing().result().plan() == null) {
                        end = "walked: " + h.landing().result().refusal();
                        break;
                    }
                    f = w.flyPlan(h.landing().result().plan(), x, sy, z, cc.withRockets(h.landing().relay() ? 2 : 1),
                        c.srv()[0], c.life(), c.srv()[1]);
                } else {
                    f = w.fly(x, sy, z, gx, gy, gz, pc, cc, c.srv()[0], c.life(), c.srv()[1]);
                    if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) {
                        end = f.why();
                        break;
                    }
                }
                String u = unsafe(w, f);
                if (u != null) return new R(c, flights, air, 0, null, "flight " + (flights + 1) + ": " + u + " [" + f.why() + "]");
                flights++;
                air += f.ticks();
                x = f.restX();
                z = f.restZ();
            }
            return new R(c, flights, air, Math.hypot(gx + 0.5 - x, gz + 0.5 - z), end, null);
        }).toList();
        List<String> bad = new ArrayList<>();
        Map<String, Integer> ends = new TreeMap<>();
        Map<String, double[]> byLeg = new TreeMap<>();
        for (R r : out) {
            if (r.bad() != null) {
                bad.add(String.format("leg %.0f heading %d vd %d life %d delay %d flag %d: %s", r.c().leg(), r.c().heading(),
                    r.c().vd(), r.c().life(), r.c().srv()[0], r.c().srv()[1], r.bad()));
                continue;
            }
            ends.merge(r.end().replaceAll("-?[0-9]+(\\.[0-9]+)?", "N"), 1, Integer::sum);
            double est = r.air() + 20.0 * r.flights() + 2.35 * r.left();
            // per leg length and view distance: cases, flights, the estimate's sum, max, the walk-left max
            double[] a = byLeg.computeIfAbsent(String.format("%.0f blocks, view distance %d", r.c().leg(), r.c().vd()),
                k -> new double[]{0, 0, 0, 0, 0, 2.35 * r.c().leg()});
            a[0]++;
            a[1] += r.flights();
            a[2] += est;
            a[3] = Math.max(a[3], est);
            a[4] = Math.max(a[4], r.left());
        }
        System.out.printf("%s: %d cases, %d unsafe%n", name, out.size(), bad.size());
        byLeg.forEach((k, a) -> System.out.printf("  %s: %.0f cases, %.1f flights on average, leg about %.0f ticks on"
            + " average (at most %.0f) against %.0f walked; walk left at most %.0f blocks%n", k, a[0], a[1] / a[0], a[2] / a[0],
            a[3], a[5], a[4]));
        ends.forEach((k, n) -> System.out.println("  end " + n + ": " + k));
        bad.stream().limit(30).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }

    /**
     * The local 10-25 case (the store to the platform: the first hop's stop lay on the line, the
     * walk from it went round the platform's no-go zone, 811 ticks against 643 with hops off), as an open field: a no-go
     * strip 6 wide from under the floor to y 190 across the line 112 blocks out, its gap 52 blocks to one side; the goal 300
     * blocks off, not loaded (the server's chunks within a view distance of 6 or 8 around the bot). Every stop lies on
     * the walk's route ({@link SimRoute}, the pathfinder's stand-in): the walk left from each landing (the route in the
     * whole world) at least 50 blocks shorter than before the flight, every flight safe. A stop on the straight line as
     * far out as the first one would have left a longer walk (printed).
     */
    @Test
    void aStopOnTheRouteNeverLengthensTheWalk() {
        record Case(boolean north, int vd, int life, int[] srv) { }
        List<Case> cases = new ArrayList<>();
        for (boolean north : new boolean[]{true, false}) {
            for (int vd : new int[]{6, 8}) {
                for (int life : new int[]{40, 51, 56}) {
                    for (int[] srv : new int[][]{{2, 3}, {4, 3}, {8, 6}}) cases.add(new Case(north, vd, life, srv));
                }
            }
        }
        record R(Case c, int flights, double walkStart, double walkEnd, double lineWalk, double firstAfter, String end,
                 String bad) { }
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        int gx = 308;
        int gy = 173;
        int gz = 8;
        List<R> out = cases.parallelStream().map(c -> {
            FlightSim w = new FlightSim().open().gated().spots(1);
            if (c.north()) w.noGo(120, 165, -100, 125, 190, 60);
            else w.noGo(120, 165, -44, 125, 190, 116);
            double x = 8.5;
            double z = 8.5;
            w.loadChunks = -1;
            double walkStart = SimRoute.length(SimRoute.route(w.terrain(), x, 173, z, gx, gy, gz, WIDE));
            double walkBefore = walkStart;
            double lineWalk = Double.NaN;
            double[] firstAfter = {Double.NaN};
            int flights = 0;
            String end = "flights used up";
            while (flights <= FlightLeg.LONG_HOPS) {
                w.loadChunks = c.vd();
                w.loadX = x;
                w.loadZ = z;
                if (w.terrain().loaded(gx, gz)) {
                    end = "the goal loaded";
                    break;
                }
                double sy = w.floorAt(x, 174.0, z);
                List<io.github.digitalisp.mapart.model.Pos> route = SimRoute.route(w.terrain(), x, sy, z, gx, gy, gz, WIDE);
                FlightLeg.Hop h = FlightLeg.withLongHop(x, sy, z, gx, gy, gz, w.terrain(), pc, cc, 30, 3, route);
                if (h.landing().result().plan() == null) {
                    end = "walked: " + h.landing().result().refusal();
                    break;
                }
                FlightSim.Flight f = w.flyPlan(h.landing().result().plan(), x, sy, z, cc.withRockets(h.landing().relay() ? 2 : 1),
                    c.srv()[0], c.life(), c.srv()[1]);
                String u = unsafe(w, f);
                if (u != null) {
                    return new R(c, flights, walkStart, 0, 0, 0, null, "flight " + (flights + 1) + ": " + u + " [" + f.why() + "]");
                }
                w.loadChunks = -1;
                if (flights == 0) {
                    // the same flight's distance on the straight line to the goal, as stage 5's stops lay
                    double d = Math.hypot(f.restX() - x, f.restZ() - z);
                    double len = Math.hypot(gx + 0.5 - x, gz + 0.5 - z);
                    double lx = x + (gx + 0.5 - x) * d / len;
                    double lz = z + (gz + 0.5 - z) * d / len;
                    // only a line point short of the strip compares (one past it is where no straight flight goes)
                    if (lx < 119) lineWalk = SimRoute.length(SimRoute.route(w.terrain(), lx, 173, lz, gx, gy, gz, WIDE));
                }
                double walkAfter = SimRoute.length(SimRoute.route(w.terrain(), f.restX(), 173, f.restZ(), gx, gy, gz, WIDE));
                if (flights == 0) firstAfter[0] = walkAfter;
                if (walkAfter > walkBefore - 50) {
                    return new R(c, flights, walkStart, walkAfter, lineWalk, firstAfter[0], null, String.format("flight %d: the walk left %.0f"
                        + " after it, %.0f before", flights + 1, walkAfter, walkBefore));
                }
                walkBefore = walkAfter;
                flights++;
                x = f.restX();
                z = f.restZ();
            }
            return new R(c, flights, walkStart, walkBefore, lineWalk, firstAfter[0], end, null);
        }).toList();
        List<String> bad = new ArrayList<>();
        Map<String, Integer> ends = new TreeMap<>();
        int flown = 0;
        double skipped = 0;
        double lineWorse = 0;
        int lineCases = 0;
        for (R r : out) {
            if (r.bad() != null) {
                bad.add(String.format("gap %s vd %d life %d delay %d flag %d: %s", r.c().north() ? "north" : "south", r.c().vd(),
                    r.c().life(), r.c().srv()[0], r.c().srv()[1], r.bad()));
                continue;
            }
            ends.merge(r.end().replaceAll("-?[0-9]+(\\.[0-9]+)?", "N"), 1, Integer::sum);
            if (r.flights() > 0) {
                flown++;
                skipped += r.walkStart() - r.walkEnd();
                if (!Double.isNaN(r.lineWalk())) {
                    lineCases++;
                    lineWorse += r.lineWalk() - r.firstAfter(); // printed only
                }
            }
        }
        System.out.printf("stops on the route: %d cases, %d flew, %d unsafe; the walk %.0f blocks at the start, %.0f skipped"
            + " on average by the flights%n", out.size(), flown, bad.size(), out.isEmpty() ? 0 : out.getFirst().walkStart(),
            flown == 0 ? 0 : skipped / flown);
        if (lineCases > 0) {
            System.out.printf("  a stop on the straight line as far out as the first one would leave %.0f blocks more to walk"
                + " on average (%d cases whose line point lies short of the strip)%n", lineWorse / lineCases, lineCases);
        }
        ends.forEach((k, n) -> System.out.println("  end " + n + ": " + k));
        bad.stream().limit(30).forEach(b -> System.out.println("  BAD " + b));
        assertTrue(bad.isEmpty(), bad.size() + " cases broke a rule, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(flown >= out.size() / 2, "most legs fly a stop on the route: " + flown + " of " + out.size());
    }

    /**
     * A goal loaded: no long hop (the leg plans as before); a route through the loaded part shorter than a straight
     * flight: no stop; no route: none either.
     */
    @Test
    void noHopWhenTheGoalIsLoadedOrTheLoadedPartIsShort() {
        FlightControl.Cfg cc = FlightControl.Cfg.forDuration(3, 3, 1, 56);
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(60, 8);
        FlightSim loaded = world(false, 200);
        FlightLeg.Hop h = FlightLeg.withLongHop(5.5, 173, 120.5, 122, 174, -3, loaded.terrain(), pc, cc, 30, 3,
            SimRoute.route(loaded.terrain(), 5.5, 173, 120.5, 122, 174, -3));
        assertNull(h.landing().result().plan());
        assertTrue(h.landing().result().refusal().contains("the goal is loaded"), h.landing().result().refusal());
        FlightSim near = world(false, 40);
        h = FlightLeg.withLongHop(30.5, 173, 10.5, 122, 174, -3, near.terrain(), pc, cc, 30, 3,
            SimRoute.route(near.terrain(), 30.5, 173, 10.5, 122, 174, -3));
        assertNull(h.landing().result().plan());
        assertNotNull(h.landing().result().refusal());
        assertTrue(h.landing().result().refusal().startsWith("no stop"), h.landing().result().refusal());
        // no route yet (the leg's start, right after a landing): no stop, the leg plans again once the walk has one
        FlightSim far = world(false, 95);
        h = FlightLeg.withLongHop(5.5, 173, 120.5, 122, 174, -3, far.terrain(), pc, cc, 30, 3, null);
        assertNull(h.landing().result().plan());
        assertTrue(FlightLeg.NO_ROUTE.equals(h.landing().result().refusal()), h.landing().result().refusal());
    }
}
