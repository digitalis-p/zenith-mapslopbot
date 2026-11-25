package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server teleport or setback during a flight (elytra stage 5). Local check 2026-10-25 (the
 * step-4 harness): a teleport came during a take-off, the flight went on with its plan from the new place and ran into
 * a wall (fly_into_wall, 4.1 HP). Here: the soul sand legs of {@link FlightOverrunTest} with the server moving the bot at
 * every stage of the flight, back along its way (a setback, with and without its speed) or by an offset (a teleport,
 * its speed ended as a command's teleport does; also far off the corridor). Before the glide the take-off ends; in the
 * air the look-ahead flies on from there with no further rocket (the burn under a boost that has no way onto the
 * landing); off the corridor it holds in place and sinks. Every case keeps the rules of
 * {@link FlightOverrunTest#unsafe}; a block met on the ground after the glide ended (walking pace) is counted apart.
 * Not covered: a jump aside that keeps full speed right before the leg's end (an earlier variant: 3 of 3024 grazed the
 * turnaround station's no-go edge or ended in the canal).
 */
class FlightTeleportTest {
    private static final int[] AT = {1, 2, 4, 10, 25, 45, 70, 95, 120};
    /** {back ticks, dx, dy, dz, keeps its speed (1), x and z toward the tile's middle (1)}. */
    private static final double[][] MOVES = {
        {5, 0, 0, 0, 0, 0}, {20, 0, 0, 0, 0, 0}, {10, 0, 0, 0, 1, 0},
        {0, 4, 0, 4, 0, 1}, {0, 0, 1, 0, 0, 0}, {0, -2, 0, 0, 0, 0}, {0, 20, 0, 20, 0, 1}};
    private static final int[] LIVES = {40, 60};

    private record Case(int kind, boolean art, boolean spots, int at, double[] move, int life, boolean saysGround) {
        String how() {
            String m = move[0] > 0 ? String.format("setback %.0f ticks%s", move[0], move[4] > 0 ? " keeping speed" : "")
                : String.format("teleport by %.0f %.0f %.0f%s%s", move[1], move[2], move[3], move[5] > 0 ? " toward the middle" : "",
                    move[4] > 0 ? " keeping speed" : "");
            return FlightOverrunTest.KINDS[kind] + (art ? " art" : " bare") + (spots ? " spot" : " pad") + " at tick " + at
                + " " + m + " life " + life + (saysGround ? " ground flag set" : "");
        }
    }

    private record Out(Case c, FlightSim.Flight f, String phase, int noGo, int fast, boolean wet, double moving,
                       int rocketsAfter) { }

    private static Out run(Case c) {
        FlightSim w = FlightOverrunTest.world(c.art(), c.spots());
        double[] l = FlightOverrunTest.leg(w, c.kind());
        w.teleportAt = c.at();
        w.teleportBack = (int) c.move()[0];
        w.tpDx = c.move()[1];
        w.tpDy = c.move()[2];
        w.tpDz = c.move()[3];
        w.teleportStops = c.move()[4] == 0;
        w.tpTowardMiddle = c.move()[5] > 0;
        w.teleportSaysGround = c.saysGround();
        FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5], FlightPlanner.Cfg.defaults(60),
            FlightControl.Cfg.forDuration(3, 3, 1, 56), 4, c.life(), 3);
        return new Out(c, f, w.lastTeleportPhase, w.lastNoGo, w.lastFastContacts, w.lastWet, w.lastMoving, f.rockets());
    }

    /** Why a case breaks the rules (null: it keeps them). */
    private static String unsafe(Out o) {
        FlightSim.Flight f = o.f();
        if (f.why().startsWith("abort: a server teleport during the take-off")) {
            if (!"JUMP".equals(o.phase()) && !"RELEASE".equals(o.phase()) && !"START".equals(o.phase()) && !"ROCKET".equals(o.phase())) {
                return "a take-off's end for a teleport in " + o.phase();
            }
            if (o.fast() > 0) return "touched a block faster than 0.3 b/t";
            if (f.maxFall() > FlightControl.TP_GLIDE_HEIGHT + 1e-9) return "fall distance " + f.maxFall();
            if (o.noGo() > 0) return "in a no-go cell";
            return null;
        }
        if ("LANDED".equals(o.phase())) {
            // a setback after the touch-down, glide off: the controller is done; the server put the bot where it was
            // in the air, the fall from there (under 3 blocks: no damage)
            return f.maxFall() > 3 ? "fall distance " + f.maxFall() + " after a setback past the touch-down" : null;
        }
        String why = FlightOverrunTest.unsafe(f, o.noGo(), o.fast(), o.wet(), o.moving());
        // a block met on the ground faster than 0.3 b/t with no glide contact: the glide had ended (walking pace,
        // no fly_into_wall); counted apart
        if ("ran into a wall".equals(why) && o.fast() == 0) return null;
        if (why != null) return why;
        if (o.rocketsAfter() > 1) return o.rocketsAfter() + " rockets";
        return null;
    }

    /**
     * The sweep: six legs, bare and built, pad and spot landings, the server moving the bot at nine stages of the flight
     * in seven ways (the setbacks also with the ground flag set in the air for a tick), boosts of 40 and 60 ticks. Every case keeps the rules; a teleport before the glide ends the take-off,
     * one in the air ends in a bail-out, never in the planned flight going on.
     */
    @Test
    void aTeleportDuringTheFlightEndsItSafely() {
        List<Case> cases = new ArrayList<>();
        for (int k = 0; k < FlightOverrunTest.KINDS.length; k++) {
            for (boolean art : new boolean[]{false, true}) {
                for (boolean spots : new boolean[]{false, true}) {
                    for (int at : AT) {
                        for (double[] m : MOVES) {
                            for (int life : LIVES) {
                                cases.add(new Case(k, art, spots, at, m, life, false));
                                // a setback whose packet says "on the ground" in the air (local 10x 2026-10-25)
                                if (m[0] > 0) cases.add(new Case(k, art, spots, at, m, life, true));
                            }
                        }
                    }
                }
            }
        }
        List<Out> out = cases.parallelStream().map(FlightTeleportTest::run).toList();
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> phases = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        List<String> bumps = new ArrayList<>();
        for (Out o : out) {
            String kind = o.f().why().startsWith("walked") || o.f().why().startsWith("refused") ? "walked"
                : o.phase() == null ? "down before the teleport" : o.f().why().replaceAll("[-0-9.]+", "N");
            kinds.merge(kind, 1, Integer::sum);
            if (o.phase() != null && !kind.equals("walked")) phases.merge(o.phase() + " -> " + kind, 1, Integer::sum);
            if (kind.equals("walked")) continue;
            String why = unsafe(o);
            if (why != null) bad.add(o.c().how() + ": " + why + " (" + o.f().why() + ")");
            else if (o.f().wall()) bumps.add(o.c().how());
        }
        StringBuilder sb = new StringBuilder(String.format("teleport sweep: %d cases, %d unsafe%n", out.size(), bad.size()));
        kinds.forEach((k, v) -> sb.append(String.format("  %5d x %s%n", v, k)));
        phases.forEach((k, v) -> sb.append(String.format("  %5d x teleport in %s%n", v, k)));
        sb.append(String.format("  %d met a block on the ground after the glide ended (no glide contact)%n", bumps.size()));
        bumps.forEach(b -> sb.append("    bump: ").append(b).append('\n'));
        System.out.print(sb);
        bad.stream().limit(40).forEach(b -> System.out.println("  unsafe: " + b));
        assertTrue(bad.isEmpty(), bad.size() + " unsafe, the first: " + (bad.isEmpty() ? "" : bad.getFirst()));
        assertTrue(phases.keySet().stream().anyMatch(k -> k.startsWith("RELEASE") || k.startsWith("START")),
            "some teleports came during the take-off");
        assertTrue(phases.keySet().stream().anyMatch(k -> k.startsWith("FLY") || k.startsWith("BRAKE")),
            "some teleports came in the glide");
    }

    /** The local case: a teleport a few blocks aside in the take-off's second tick ends the take-off, no damage. */
    @Test
    void aTeleportInTheTakeOffEndsIt() {
        FlightSim w = FlightOverrunTest.world(false, false);
        double[] l = FlightOverrunTest.leg(w, 0);
        w.teleportAt = 2;
        w.tpDx = 3;
        w.tpDz = -2;
        FlightSim.Flight f = w.fly(l[0], l[1], l[2], (int) l[3], (int) l[4], (int) l[5], FlightPlanner.Cfg.defaults(60),
            FlightControl.Cfg.forDuration(3, 3, 1, 56), 4, 51, 3);
        System.out.println("teleport in the take-off (" + w.lastTeleportPhase + "): " + f);
        assertEquals("abort: a server teleport during the take-off", f.why());
        assertEquals(0, w.lastFastContacts);
        assertTrue(f.maxFall() <= 1.25, "fall " + f.maxFall());
    }
}
