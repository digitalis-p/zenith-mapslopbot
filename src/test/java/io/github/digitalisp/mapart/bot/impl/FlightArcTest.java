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
 * Arc flights ({@code build.elytraArcLegs}): the way's
 * geometry ({@link FlightPath}), and the sweeps over the soul sand model ({@link FlightSim}): every leg kind from 40 to
 * 90 blocks, under the end rods over the bare tile and over a dense built art layer, boosts from 39 ticks to the worst
 * planned for (53, and 66 for a lagging server), round trips of 2 to 8 ticks; the open field; the canals; a boost that
 * ends early and a bail-out at every third tick. Each leg either walks or lands on its pad touching no block. The sweeps
 * run their cases in parallel (each its own world) and print their counts.
 */
class FlightArcTest {
    private static final int[][] SERVERS = {{2, 31, 2}, {4, 25, 3}, {8, 20, 6}};
    static final String[] KINDS = {"lane z", "lane x", "lane diagonal", "out west", "out middle", "back", "walkway west",
        "walkway east", "walkway middle", "drop point", "map station"};

    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
    }

    /** The soul sand model under the end rods; {@code art}: with a dense built art layer (candles and full blocks). */
    private static FlightSim world(boolean art) {
        FlightSim w = new FlightSim().lights().gated();
        if (art) w.art(40, 40);
        w.arcMin = 40;
        return w;
    }

    /** Leg kind {@code k} of {@link #KINDS}, {@code d} blocks: from feet {x, y, z} to goal cell {x, y, z}. */
    static double[] leg(FlightSim w, int k, int d) {
        double c45 = Math.cos(Math.toRadians(45));
        double c30 = Math.cos(Math.toRadians(30));
        double s30 = Math.sin(Math.toRadians(30));
        return switch (k) {
            // art to art, a lane's start (LANE_START)
            case 0 -> new double[]{20.5, feet(w, 20.5, 30.5), 30.5, 20, 173, 30 + d};
            case 1 -> new double[]{20.5, feet(w, 20.5, 90.5), 90.5, 20 + d, 173, 90};
            case 2 -> new double[]{30.5, feet(w, 30.5, 30.5), 30.5, Math.floor(30 + d * c45), 173, Math.floor(30 + d * c45)};
            // art to the walkway's chest stand (CONTAINER), west of the rods' columns and under them
            case 3 -> new double[]{20.5, feet(w, 20.5, d - 2.5), d - 2.5, 20, 174, -3};
            case 4 -> new double[]{64.5, feet(w, 64.5, d - 2.5), d - 2.5, 64, 174, -3};
            // the walkway to the art (LANE_START after a restock)
            case 5 -> new double[]{40.5, 174, -2.5, 40, 173, d - 3};
            // along the walkway, chest to chest (CONTAINER): from near the east canal, from near the west one, the middle
            case 6 -> new double[]{118.5, 174, -3.5, 118 - d, 174, -2};
            case 7 -> new double[]{8.5, 174, -2.5, 8 + d, 174, -3};
            case 8 -> new double[]{64.5 - d / 2.0, 174, -2.5, 64 + d / 2, 174, -2};
            // the art to the walkway's west end on a diagonal (DROP_POINT)
            case 9 -> {
                double x = 0.5 + d * c45;
                double z = d * c45 - 1.5;
                yield new double[]{x, feet(w, x, z), z, 0, 174, -2};
            }
            // the art to the map stand under the counter's line (MAP_STATION)
            default -> {
                double x = 63.5 - d * c30;
                double z = 60.5 - d * s30;
                yield new double[]{x, feet(w, x, z), z, 63, 173, 60};
            }
        };
    }

    private record Case(boolean art, int worst, int life, int[] srv, int dist, int kind) {
        String how() {
            return KINDS[kind] + " " + dist + (art ? " art" : " bare") + " worst " + worst + " life " + life + " delay "
                + srv[0] + " flag " + srv[2];
        }
    }

    /** One flown or walked case: the flight, and the arc's numbers (NaN when straight or walked). */
    private record Outcome(Case c, FlightSim.Flight f, boolean arc, int contacts, boolean wet, double offTurn, double off,
                           double boostMargin, int rockets) {
        boolean walked() {
            return f.why().startsWith("walked: ") || f.why().startsWith("refused: ");
        }
    }

    private static Outcome run(Case c) {
        FlightSim w = world(c.art());
        double[] l = leg(w, c.kind(), c.dist());
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, c.worst());
        FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5], FlightPlanner.Cfg.defaults(60), d3,
            c.srv()[0], c.life(), c.srv()[2]);
        boolean arc = w.lastArc != null;
        double margin = arc ? w.lastBoostEndAt - w.lastArc.turnEnd() : Double.NaN;
        return new Outcome(c, f, arc, w.lastContacts, w.lastWet, w.lastMaxOffTurn, w.lastMaxOff, margin, f.rockets());
    }

    /** Why a flown case breaks the landing rules (null: it keeps them): the restock test's rules, one rocket. */
    static String unsafe(FlightSim.Flight f, int contacts, boolean wet) {
        if (!f.why().equals("landed")) return f.why();
        if (contacts > 0) return "touched a block in the air " + contacts + " times";
        if (f.wall()) return "ran into a wall";
        if (wet) return "came down in water";
        if (f.restErr() > FlightControl.REST_PAST_MAX || f.restErr() < -12) return "rest " + f.restErr() + " off R";
        if (Math.abs(f.across()) >= 1.5) return f.across() + " across";
        if (f.touchSpeed() > 1.8) return f.touchSpeed() + " b/t at the touch-down";
        if (f.touchVy() <= -0.5) return "sink " + f.touchVy() + " at the touch-down";
        if (f.maxFall() > 1.0 + 1e-9) return "fall distance " + f.maxFall();
        if (f.rockets() != 1) return "rockets " + f.rockets();
        return null;
    }

    // ---- the way's geometry

    @Test
    void aWayIsAStraightLineATurnAndTheFinalLineIntoR() {
        // from the origin to R at (0, 40), the final heading north (-z) 40 blocks: a loop out of the way and back
        for (int sense : new int[]{1, -1}) {
            for (double radius : FlightPlanner.ARC_RADII) {
                FlightPath p = FlightPath.of(0.5, 0.5, 30.5, 60.5, 0, -1, 40, radius, sense);
                assertNotNull(p, "radius " + radius + " sense " + sense);
                double[] r = p.point(p.length());
                assertEquals(30.5, r[0], 1e-6);
                assertEquals(60.5, r[1], 1e-6);
                assertEquals(p.first + p.turn + p.last, p.length(), 1e-9);
                assertEquals(40, p.last, 1e-6);
                // the heading turns smoothly: no kink at A or at B
                for (double s : new double[]{p.first, p.turnEnd()}) {
                    double[] a = p.heading(s - 1e-6);
                    double[] b = p.heading(s + 1e-6);
                    assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) < 1e-3, "kink at " + s + ": " + p);
                }
                // the points follow each other a block apart along the way
                double[] prev = p.point(0);
                for (double s = 0.5; s <= p.length(); s += 0.5) {
                    double[] q = p.point(s);
                    assertTrue(Math.hypot(q[0] - prev[0], q[1] - prev[1]) <= 0.5 + 1e-6, "a gap at " + s + ": " + p);
                    prev = q;
                }
                assertEquals(0, p.heading(p.length())[0], 1e-9);
                assertEquals(-1, p.heading(p.length())[1], 1e-9);
            }
        }
        // the take-off spot within the turn's circle: no way
        assertNull(FlightPath.of(0.5, 0.5, 0.5, -40, 0, -1, 40.5, 8, 1));
    }

    @Test
    void theBotsPlaceOnALoopThatCrossesItselfFollowsTheWay() {
        // a 40-block leg's loop (as planned for a lane's start): the way crosses its own take-off line, where the
        // nearest point overall would jump to the other crossing
        FlightPath p = null;
        for (int k = 0; k < FlightPlanner.ARC_HEADINGS && p == null; k++) {
            double a = 2 * Math.PI * k / FlightPlanner.ARC_HEADINGS;
            for (double fl = FlightPlanner.ARC_FINAL_MIN; fl <= FlightPlanner.ARC_FINAL_MAX && p == null; fl += 3) {
                FlightPath w = FlightPath.of(0.5, 0.5, 0.5, 40.5, Math.cos(a), Math.sin(a), fl, 8, 1);
                if (w != null && w.turnDegrees() > 250 && w.turnEnd() <= FlightPlanner.ARC_TURN_END) p = w;
            }
        }
        assertNotNull(p, "a loop of more than 250 degrees");
        double at = 0;
        for (double s = 0; s <= p.length(); s += 1.5) {
            double[] q = p.point(s);
            double[] h = p.heading(s);
            at = p.track(q[0] - h[1] * 0.4, q[1] + h[0] * 0.4, at); // a little off the way, as the bot flies
            assertEquals(s, at, 1.0, "tracked at " + s + " of " + p);
        }
        // past R on along the final line
        assertEquals(p.length() + 3, p.track(p.rx + p.finalX * 3, p.rz + p.finalZ * 3, p.length() - 1), 1e-6);
    }

    @Test
    void arcFlightsAreOffByDefault() {
        io.github.digitalisp.mapart.config.MapArtConfig.BuildCfg b = new io.github.digitalisp.mapart.config.MapArtConfig.BuildCfg();
        assertFalse(b.elytraArcLegs, "arc flights off by default");
        assertEquals(60, b.elytraArcMinBlocks);
        assertEquals(b.elytraMinBlocks, FlightLeg.minBlocks(b), "the shortest leg as before with arcs off");
        b.elytraArcLegs = true;
        b.elytraArcMinBlocks = 40;
        assertEquals(Math.min(b.elytraMinBlocks, 40), FlightLeg.minBlocks(b), "with arcs on, the shorter of the two");
    }

    /** A leg too long for any arc's way (the pad within 24 of the goal) is refused before the pad checks, saying so. */
    @Test
    void aLegTooLongForAnyWayIsRefusedSayingSo() {
        FlightSim w = new FlightSim().lights().gated();
        FlightPlanner.ArcResult r = FlightPlanner.planArc(5.5, feet(w, 5.5, 5.5), 5.5, 120, 173, 120, w.terrain(),
            FlightPlanner.Cfg.defaults(60), 40, 95);
        assertTrue(r.plans().isEmpty());
        assertEquals("163 blocks: no arc's way of 95 to 130 blocks reaches a pad within 24 of the goal", r.refusal());
    }

    // ---- the sweeps

    /**
     * Every leg kind from 40 to 90 blocks, under the straight flight's shortest leg (95 blocks with boosts up to 53
     * ticks, 115 up to 66): each one walks or lands on its pad by an arc, touching no block, never in water, at most
     * 1.8 b/t and sinking under 0.5 at the touch-down, no fall damage, one rocket. Most of them fly.
     */
    @Test
    void arcLegsFromFortyBlocksLandOnThePadOrWalk() {
        List<Case> cases = new ArrayList<>();
        for (boolean art : new boolean[]{false, true}) {
            for (int worst : new int[]{53, 66}) {
                int[] lives = worst == 53 ? new int[]{39, 46, 53} : new int[]{40, 66};
                for (int life : lives) {
                    for (int[] srv : SERVERS) {
                        for (int dist = 40; dist <= 90; dist += 10) {
                            for (int k = 0; k < KINDS.length; k++) cases.add(new Case(art, worst, life, srv, dist, k));
                        }
                    }
                }
            }
        }
        List<Outcome> out = cases.parallelStream().map(FlightArcTest::run).toList();
        Map<String, int[]> byKind = new TreeMap<>(); // flown, total (worst 53)
        Map<String, int[]> byWorst = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        double offTurn = 0;
        double off = 0;
        double margin = Double.POSITIVE_INFINITY;
        int flown = 0;
        int arcs = 0;
        for (Outcome o : out) {
            String w = "worst " + o.c().worst() + (o.c().art() ? " art" : " bare");
            byWorst.computeIfAbsent(w, k -> new int[2])[1]++;
            if (o.c().worst() == 53) byKind.computeIfAbsent(KINDS[o.c().kind()], k -> new int[2])[1]++;
            if (o.walked()) continue;
            flown++;
            if (o.arc()) arcs++;
            byWorst.get(w)[0]++;
            if (o.c().worst() == 53) byKind.get(KINDS[o.c().kind()])[0]++;
            String why = unsafe(o.f(), o.contacts(), o.wet());
            if (why != null) bad.add(o.c().how() + ": " + why);
            if (o.arc()) {
                offTurn = Math.max(offTurn, o.offTurn());
                off = Math.max(off, o.off());
                if (!Double.isNaN(o.boostMargin())) margin = Math.min(margin, o.boostMargin());
            }
        }
        StringBuilder sb = new StringBuilder(String.format("arc sweep: %d cases, %d flown (%d arcs), %d walked, %d unsafe;"
            + " most off the way %.2f in the turn, %.2f on the final line; the boost ended %.1f blocks past the turn at the"
            + " least%n", out.size(), flown, arcs, out.size() - flown, bad.size(), offTurn, off, margin));
        byWorst.forEach((k, v) -> sb.append(String.format("  %s: %d of %d flown%n", k, v[0], v[1])));
        byKind.forEach((k, v) -> sb.append(String.format("  %s (worst 53): %d of %d flown%n", k, v[0], v[1])));
        System.out.print(sb);
        bad.stream().limit(40).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        int[] bare53 = byWorst.get("worst 53 bare");
        assertTrue(bare53[0] * 4 >= bare53[1] * 3, "most legs fly under the rods over the bare tile: " + bare53[0] + " of "
            + bare53[1]);
    }

    /**
     * Live 2026-10-25 13:27 (c4d3e3a): an arc CONTAINER leg of 99 blocks (33 straight, a turn of 32 degrees at
     * radius 14, 64 straight into R on the walkway) met a block in FLY at 0.83 b/t, 1.05 over the soul sand (a full
     * block of the art beside it, light blue terracotta), and came down 14.8 before R, 3.0 across. Here: legs of 90 to
     * 105 blocks from the art onto the walkway's chest stands, at 8 to 40 degrees off the walkway, over an art layer with
     * full blocks in about 3 cells of 10 (a terracotta map) and carpets: no flight touches a block in the air or runs
     * into a wall, whatever the boost (39 to 66 ticks) and the server.
     */
    @Test
    void arcLegsOntoTheWalkwayOverDenseFullBlocksTouchNothing() {
        record W(int goalX, int goalZ, int dist, int bearing, int worst, int life, int[] srv) { }
        List<W> cases = new ArrayList<>();
        for (int[] g : new int[][]{{20, -3}, {40, -2}}) {
            for (int dist : new int[]{90, 95, 99, 105}) {
                for (int bearing : new int[]{8, 16, 24, 32, 40}) {
                    for (int[] wl : new int[][]{{53, 20}, {53, 28}, {53, 34}, {53, 39}, {53, 46}, {53, 53}, {66, 28}, {66, 40}, {66, 53}, {66, 66}}) {
                        for (int[] srv : SERVERS) cases.add(new W(g[0], g[1], dist, bearing, wl[0], wl[1], srv));
                    }
                }
            }
        }
        record O(W c, FlightSim.Flight f, boolean arc, int contacts, boolean wet, double across) { }
        List<O> out = cases.parallelStream().map(c -> {
            FlightSim w = new FlightSim().lights().gated();
            w.art(40, 300);
            w.arcMin = 40;
            double a = Math.toRadians(c.bearing());
            double sx = c.goalX() + 0.5 + c.dist() * Math.cos(a);
            double sz = c.goalZ() + 0.5 + c.dist() * Math.sin(a);
            if (sx > 126 || sz > 126) return null;
            FlightSim.Flight f = w.fly(sx, feet(w, sx, sz), sz, c.goalX(), 174, c.goalZ(), FlightPlanner.Cfg.defaults(60),
                FlightControl.Cfg.forDuration(3, 3, 1, c.worst()), c.srv()[0], c.life(), c.srv()[2]);
            return new O(c, f, w.lastArc != null, w.lastContacts, w.lastWet, f.across());
        }).filter(java.util.Objects::nonNull).toList();
        int flown = 0;
        int arcs = 0;
        List<String> bad = new ArrayList<>();
        Map<String, Integer> walked = new TreeMap<>();
        for (O o : out) {
            if (o.f().why().startsWith("walked: ") || o.f().why().startsWith("refused: ")) {
                walked.merge(o.f().why().replaceAll("-?[0-9]+(\\.[0-9]+)?", "N").substring(0,
                    Math.min(160, o.f().why().replaceAll("-?[0-9]+(\\.[0-9]+)?", "N").length())), 1, Integer::sum);
                continue;
            }
            flown++;
            if (o.arc()) arcs++;
            String how = String.format("goal %d,%d %d blocks at %d degrees worst %d life %d delay %d flag %d (%s)",
                o.c().goalX(), o.c().goalZ(), o.c().dist(), o.c().bearing(), o.c().worst(), o.c().life(), o.c().srv()[0],
                o.c().srv()[2], o.f().why());
            if (o.contacts() > 0) bad.add(how + ": touched a block in the air " + o.contacts() + " times");
            else if (o.f().wall()) bad.add(how + ": ran into a wall");
            else if (o.wet()) bad.add(how + ": came down in water");
            else if (o.f().maxFall() > 1.0 + 1e-9) bad.add(how + ": fall distance " + o.f().maxFall());
            else if (o.f().touchVy() <= -0.5) bad.add(how + ": sink " + o.f().touchVy());
        }
        System.out.printf("arcs onto the walkway over dense full blocks: %d cases, %d flown (%d arcs), %d unsafe%n",
            out.size(), flown, arcs, bad.size());
        walked.forEach((k, n) -> System.out.println("  walked " + n + ": " + k));
        bad.stream().limit(40).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }

    /**
     * The look-ahead's margin for a live server's error: the walkway legs of
     * {@link #arcLegsOntoTheWalkwayOverDenseFullBlocksTouchNothing} with the server's glide sinking 0.01 b/t a tick more
     * than the controller's model (an eighth more than gravity's pull, every tick in the air): the controller's
     * re-planning absorbs it, no flight meets a block at a speed that harms (over 0.3 b/t) or falls. Slow touches are
     * counted, not failed. (At 0.02 b/t, a quarter more, rockets 2 to 6 ticks late still hold; 8 ticks late the
     * take-off's glide before the boost sinks under a full block of the art beside it and the boost pushes into it.)
     */
    @Test
    void walkwayArcsHoldWhenTheServersGlideSinksMoreThanTheModel() {
        record W(int goalX, int goalZ, int dist, int bearing, int worst, int life, int[] srv) { }
        List<W> cases = new ArrayList<>();
        for (int[] g : new int[][]{{20, -3}, {40, -2}}) {
            for (int dist : new int[]{90, 99, 105}) {
                for (int bearing : new int[]{8, 16, 24, 32, 40}) {
                    for (int[] wl : new int[][]{{53, 28}, {53, 39}, {53, 53}, {66, 40}, {66, 66}}) {
                        for (int[] srv : SERVERS) cases.add(new W(g[0], g[1], dist, bearing, wl[0], wl[1], srv));
                    }
                }
            }
        }
        List<String> out = cases.parallelStream().map(c -> {
            FlightSim w = new FlightSim().lights().gated();
            w.art(40, 300);
            w.arcMin = 40;
            w.sinkBias = 0.01;
            double a = Math.toRadians(c.bearing());
            double sx = c.goalX() + 0.5 + c.dist() * Math.cos(a);
            double sz = c.goalZ() + 0.5 + c.dist() * Math.sin(a);
            if (sx > 126 || sz > 126) return null;
            FlightSim.Flight f = w.fly(sx, feet(w, sx, sz), sz, c.goalX(), 174, c.goalZ(), FlightPlanner.Cfg.defaults(60),
                FlightControl.Cfg.forDuration(3, 3, 1, c.worst()), c.srv()[0], c.life(), c.srv()[2]);
            if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) return "walked";
            String how = String.format("goal %d,%d %d blocks at %d degrees worst %d life %d delay %d (%s)", c.goalX(),
                c.goalZ(), c.dist(), c.bearing(), c.worst(), c.life(), c.srv()[0], f.why());
            if (w.lastFastContacts > 0) return how + ": met a block at speed " + w.lastFastContacts + " times";
            if (f.wall()) return how + ": ran into a wall";
            if (f.maxFall() > 1.0 + 1e-9) return how + ": fall distance " + f.maxFall();
            return w.lastContacts > 0 ? "slow" : "flown";
        }).filter(java.util.Objects::nonNull).toList();
        long flown = out.stream().filter(s -> s.equals("flown") || s.equals("slow")).count();
        long slow = out.stream().filter("slow"::equals).count();
        List<String> bad = out.stream().filter(s -> !s.equals("walked") && !s.equals("flown") && !s.equals("slow")).toList();
        System.out.printf("walkway arcs, the server's glide 0.01 b/t lower a tick: %d cases, %d flown, %d unsafe, %d"
            + " touched a block slowly%n", out.size(), flown + bad.size(), bad.size(), slow);
        bad.stream().limit(20).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(flown > 50, "flown " + flown);
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }

    /**
     * The open field (no walkway, wall, canal, rods): legs of 40 to 90 blocks in eight directions, boosts of 39 to 53
     * ticks: every one lands on its pad by an arc touching nothing.
     */
    @Test
    void arcLegsOverTheOpenFieldLand() {
        List<Case> cases = new ArrayList<>();
        for (int life : new int[]{39, 46, 53}) {
            for (int[] srv : SERVERS) {
                for (int dist = 42; dist <= 92; dist += 10) {
                    for (int dir = 0; dir < 8; dir++) cases.add(new Case(false, 53, life, srv, dist, dir));
                }
            }
        }
        List<Outcome> out = cases.parallelStream().map(c -> {
            FlightSim w = new FlightSim().open().gated();
            w.arcMin = 40;
            double a = Math.toRadians(45 * c.kind() + 10);
            double gx = 300 + c.dist() * Math.cos(a);
            double gz = 300 + c.dist() * Math.sin(a);
            FlightSim.Flight f = w.fly(300.5, 173, 300.5, (int) Math.floor(gx), 173, (int) Math.floor(gz),
                FlightPlanner.Cfg.defaults(60), FlightControl.Cfg.forDuration(3, 3, 1, 53), c.srv()[0], c.life(), c.srv()[2]);
            return new Outcome(c, f, w.lastArc != null, w.lastContacts, w.lastWet, w.lastMaxOffTurn, w.lastMaxOff,
                Double.NaN, f.rockets());
        }).toList();
        int flown = 0;
        List<String> bad = new ArrayList<>();
        for (Outcome o : out) {
            if (o.walked()) {
                bad.add("open field dir " + o.c().kind() + " " + o.c().dist() + " walked: " + o.f().why());
                continue;
            }
            flown++;
            String why = unsafe(o.f(), o.contacts(), o.wet());
            if (why != null) bad.add("open field dir " + o.c().kind() + " " + o.c().dist() + " life " + o.c().life() + ": " + why);
        }
        System.out.printf("arc open field: %d cases, %d flown, %d unsafe or walked%n", out.size(), flown, bad.size());
        bad.stream().limit(60).forEach(b -> System.out.println("  " + b));
        assertTrue(bad.isEmpty(), bad.size() + " walked or unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }

    /**
     * Legs beside the canals (the tile's west and east edges, the walkway's ends): no arc's corridor holds water
     * within a straight corridor's half width of its way, and no flight, nor one whose boost ends early (5 to 30 ticks)
     * or that bails out at every third tick, comes down in the water.
     */
    @Test
    void arcsBesideTheCanalsNeverComeDownInTheWater() {
        record Edge(String name, double sx, double sz, int gx, int gz) { }
        List<Edge> edges = new ArrayList<>();
        for (int d = 40; d <= 90; d += 10) {
            edges.add(new Edge("west edge north " + d, 2.5, 20.5, 2, 20 + d));
            edges.add(new Edge("west edge south " + d, 2.5, 110.5, 2, 110 - d));
            edges.add(new Edge("east edge " + d, 125.5, 20.5, 125, 20 + d));
            edges.add(new Edge("walkway east end " + d, 124.5, -2.5, 124 - d, -2));
            edges.add(new Edge("walkway west end " + d, 3.5, -2.5, 3 + d, -2));
        }
        List<String> bad = new ArrayList<>();
        int[] counts = new int[4]; // cases, flown, gone wrong, wet
        List<Object[]> runs = new ArrayList<>();
        for (Edge e : edges) {
            for (int[] srv : SERVERS) {
                for (int life : new int[]{5, 10, 20, 30, 39, 53}) runs.add(new Object[]{e, srv, life, -1});
            }
        }
        List<String[]> res = runs.parallelStream().map(r -> {
            Edge e = (Edge) r[0];
            int[] srv = (int[]) r[1];
            int life = (int) r[2];
            FlightSim w = world(false);
            double sy = e.sz() < 0 ? 174 : feet(w, e.sx(), e.sz());
            int gy = e.gz() < 0 ? 174 : 173;
            FlightSim.Flight f = w.fly(e.sx(), sy, e.sz(), e.gx(), gy, e.gz(), FlightPlanner.Cfg.defaults(60),
                FlightControl.Cfg.forDuration(3, 3, 1, 53), srv[0], life, srv[2]);
            String how = e.name() + " life " + life + " delay " + srv[0];
            if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) return new String[]{how, "walked", null};
            String why = null;
            if (w.lastWet) why = "came down in the water at y " + w.lastY;
            if (w.lastArc != null) {
                for (long k : w.lastPlan.profile().water) {
                    // water only out in the apron, never within the straight corridor's half width of the way
                    int x = (int) (k >> 32);
                    int z = (int) k;
                    double best = Double.POSITIVE_INFINITY;
                    for (double s = 0; s <= w.lastArc.length(); s += 0.5) {
                        double[] q = w.lastArc.point(s);
                        best = Math.min(best, Math.hypot(x + 0.5 - q[0], z + 0.5 - q[1]));
                    }
                    if (best <= FlightPlanner.Cfg.defaults(60).halfWidth() + 0.71 - 0.5) why = "water at " + x + "," + z
                        + " " + best + " off the way";
                }
            }
            if (life >= 39 && why == null) why = unsafe(f, w.lastContacts, w.lastWet);
            return new String[]{how, life >= 39 ? "flown" : "gone wrong", why};
        }).toList();
        for (String[] r : res) {
            counts[0]++;
            if (r[1].equals("flown")) counts[1]++;
            if (r[1].equals("gone wrong")) counts[2]++;
            if (r[2] != null) bad.add(r[0] + ": " + r[2]);
        }
        System.out.printf("arcs beside the canals: %d cases, %d flown, %d whose boost ended early, %d in the water or"
            + " unsafe%n", counts[0], counts[1], counts[2], bad.size());
        assertTrue(bad.isEmpty(), bad.size() + " wrong, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
    }

    /**
     * A boost that ends early (5 to 30 ticks against the 40-53 planned for) on arc legs of every kind, over the bare tile
     * and the dense art: the bot comes down touching no block, at most 1.8 b/t, sinking under 0.5, no fall damage, never
     * in water; where, is its own to choose. A bail-out forced at every third tick (a walk stopped in the air,
     * {@link FlightControl#endInTheAir}; the rocket may still boost): at most one in a hundred touches a block (straight
     * flights over the same tile, the simulation 2026-10-25: 231 of 2171 runs, bail-outs and early ends, touched one).
     */
    @Test
    void anArcWhoseBoostEndsEarlyOrThatBailsOutComesDownSafely() {
        List<Object[]> runs = new ArrayList<>();
        for (boolean art : new boolean[]{false, true}) {
            for (int k = 0; k < KINDS.length; k++) {
                for (int dist : new int[]{50, 70, 90}) {
                    FlightSim probe = world(art);
                    double[] l = leg(probe, k, dist);
                    FlightSim.Flight f = probe.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5],
                        FlightPlanner.Cfg.defaults(60), FlightControl.Cfg.forDuration(3, 3, 1, 53), 4, 46, 3);
                    if (probe.lastArc == null || !f.why().equals("landed")) continue;
                    for (int[] srv : SERVERS) {
                        for (int life : new int[]{5, 10, 20, 30}) runs.add(new Object[]{art, k, dist, srv, life, -1});
                    }
                    for (int t = 1; t <= f.ticks(); t += 3) runs.add(new Object[]{art, k, dist, SERVERS[1], 46, t});
                }
            }
        }
        List<String[]> res = runs.parallelStream().map(r -> {
            boolean art = (boolean) r[0];
            int k = (int) r[1];
            int dist = (int) r[2];
            int[] srv = (int[]) r[3];
            int life = (int) r[4];
            int bail = (int) r[5];
            FlightSim w = world(art);
            w.bailAt = bail;
            double[] l = leg(w, k, dist);
            FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5],
                FlightPlanner.Cfg.defaults(60), FlightControl.Cfg.forDuration(3, 3, 1, 53), srv[0], life, srv[2]);
            String how = KINDS[k] + " " + dist + (art ? " art" : " bare") + " life " + life + " delay " + srv[0]
                + " bail at " + bail;
            String why = null;
            if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) why = "walked: " + f.why();
            else if (f.why().equals("timeout")) why = f.why(); // an abort: still on the ground, the walk goes on
            else if (w.lastContacts > 0) why = "touched a block in the air " + w.lastContacts + " times";
            else if (f.wall()) why = "ran into a wall";
            else if (w.lastWet) why = "came down in water";
            // a fall of more than 3 blocks hurts (a bail-out at its first tick drops 1.1 before the glide opens)
            else if (f.maxFall() > 3.0) why = "fall distance " + f.maxFall();
            else if (f.touchSpeed() > 1.8) why = f.touchSpeed() + " b/t at the touch-down";
            else if (f.touchVy() <= -0.5) why = "sink " + f.touchVy() + " at the touch-down";
            return new String[]{how, why, String.format("%.2f", f.maxFall())};
        }).toList();
        List<String> early = new ArrayList<>();
        List<String> bails = new ArrayList<>();
        int earlyCases = 0;
        int touched = 0;
        double worstFall = 0;
        for (String[] r : res) {
            boolean isEarly = r[0].endsWith("bail at -1");
            if (isEarly) earlyCases++;
            if (r[1] != null) (isEarly ? early : bails).add(r[0] + ": " + r[1]);
            if (!isEarly && r[1] != null && (r[1].startsWith("touched") || r[1].startsWith("ran into"))) touched++;
            worstFall = Math.max(worstFall, Double.parseDouble(r[2]));
        }
        int bailCases = res.size() - earlyCases;
        System.out.printf("arcs gone wrong: %d whose boost ended early, %d unsafe; %d bail-outs, %d unsafe (%d touched a"
            + " block or a wall); worst fall %.2f%n", earlyCases, early.size(), bailCases, bails.size(), touched, worstFall);
        early.forEach(b -> System.out.println("  early: " + b));
        Map<String, Long> kinds = new TreeMap<>();
        for (String b : bails) kinds.merge(b.substring(b.indexOf(": ") + 2).replaceAll("[-0-9.]+", "N"), 1L, Long::sum);
        kinds.forEach((kk, v) -> System.out.println("  bail-outs: " + v + " x " + kk));
        assertTrue(earlyCases > 200 && bailCases > 200, "cases " + earlyCases + ", " + bailCases);
        assertTrue(early.isEmpty(), early.size() + " unsafe, the first: " + (early.isEmpty() ? "" : early.getFirst()));
        assertTrue(touched * 100 <= bailCases, touched + " bail-outs of " + bailCases + " touched a block");
    }
}
