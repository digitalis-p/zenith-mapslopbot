package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlightControl} flying the {@link FlightSim} Bot (ZenithProxy's glide and boost, a server that clears the glide
 * flag a few ticks after the touch-down): the take-off, the landing on the pad without fall damage, the fall-back rules.
 */
class FlightControlTest {
    private static final FlightControl.Cfg CC = FlightControl.Cfg.defaults();
    /** Rocket delay / boost ticks / ticks until the server ends the glide: as local, as 2b2t, slow. */
    private static final int[][] SERVERS = {{2, 31, 2}, {4, 25, 3}, {8, 20, 6}};

    /** The worlds of the restock test: the bare platform, the end rods over it, the rods and a built art layer. */
    private static FlightSim world(int kind) {
        return switch (kind) {
            case 0 -> new FlightSim();
            case 1 -> new FlightSim().lights();
            default -> new FlightSim().lights().art();
        };
    }

    private static final String[] WORLDS = {"", " under the end rods", " under the end rods over the art"};

    /** The feet height standing at (x, z) of {@code w} (the art layer's carpet, candle or block, else the floor). */
    private static double feet(FlightSim w, double x, double z) {
        return w.floorAt(x, 174.0, z);
    }

    @Test
    void restockLegsLandOnThePadWithoutFallDamage() {
        int[] artLegs = new int[2]; // over the art: legs, refused for no pad (a candle or a block in every strip)
        for (int kind = 0; kind < WORLDS.length; kind++) {
            for (int[] s : SERVERS) {
                String how = java.util.Arrays.toString(s) + WORLDS[kind];
                for (int dist : new int[]{60, 80, 100, 130, 160}) {
                    // walk-out: tile to the walkway's chest stand (x 20: no rod on the line; x 23: rods at x 23)
                    for (double sx : new double[]{20.5, 23.5}) {
                        FlightSim w = world(kind);
                        double sz = Math.min(126.5, dist - 2.5);
                        FlightSim.Flight f = w.fly(sx, feet(w, sx, sz), sz, (int) sx, 174, -3,
                            FlightPlanner.Cfg.defaults(50), CC, s[0], s[1], s[2]);
                        check("out " + dist + " x " + sx + " " + how, f, kind, artLegs);
                    }
                }
                for (int dist : new int[]{60, 80, 100, 125}) {
                    // walk-back: walkway to the lane start on the tile (x 47: under a row of rods)
                    for (double sx : new double[]{40.5, 47.5}) {
                        FlightSim w = world(kind);
                        FlightSim.Flight f = w.fly(sx, 174, -2.5, (int) sx, 173, dist - 3, FlightPlanner.Cfg.defaults(50), CC,
                            s[0], s[1], s[2]);
                        check("back " + dist + " x " + sx + " " + how, f, kind, artLegs);
                    }
                }
                // along the walkway, chest to chest (fly3's run into a carpet's edge: a pad on the art beside it)
                for (int dist : new int[]{60, 63, 90}) {
                    FlightSim w = world(kind);
                    check("walkway " + dist + " " + how, w.fly(118.5, 174, -3.5, 118 - dist, 174, -2,
                        FlightPlanner.Cfg.defaults(50), CC, s[0], s[1], s[2]), kind, artLegs);
                }
                // diagonal across the tile and the counter's line
                FlightSim w = world(kind);
                check("diagonal " + how, w.fly(10.5, feet(w, 10.5, 120.5), 120.5, 100, 173, 10, FlightPlanner.Cfg.defaults(50),
                    CC, s[0], s[1], s[2]), kind, artLegs);
            }
        }
        assertTrue(artLegs[1] * 2 <= artLegs[0], "over the art " + artLegs[1] + " of " + artLegs[0] + " legs found no pad");
    }

    @Test
    void aJumpWhoseInputWentElsewhereIsPressedAgain() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 100.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        assertNotNull(r.plan());
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        FlightSim.Bot b = new FlightSim.Bot(w, 40.5, 172.875, 100.5);
        int jumps = 0;
        String why = null;
        for (int i = 0; i < 400; i++) {
            FlightControl.Keys k = c.tick(b.in());
            if (k.abort() != null || k.done()) {
                why = k.abort() != null ? k.abort() : "landed";
                break;
            }
            if (k.jump() && b.onGround) jumps++;
            // the first jump's tick went with another request's input: the Bot did not jump
            b.step(jumps == 1 && k.jump() ? new FlightControl.Keys(k.yaw(), k.pitch(), false, false, false, null) : k);
        }
        assertEquals("landed", why);
        assertEquals(2, jumps, "a second jump");
        assertFalse(b.hitWall);
    }

    /**
     * Refusals over the art layer (no clean pad near the goal) count; every flight must land. Over the art the bot may
     * come to rest up to 6 blocks short of R (a glide that skims the carpets would run into their edges: it lands on
     * the first floor it can reach instead).
     */
    private static void check(String what, FlightSim.Flight f, int kind, int[] artLegs) {
        if (kind == 2) {
            artLegs[0]++;
            if (f.why().startsWith("refused: no landing pad")) {
                artLegs[1]++;
                return;
            }
            check(what, f, 6.0);
            return;
        }
        check(what, f);
    }

    private static void check(String what, FlightSim.Flight f) {
        check(what, f, 4.0);
    }

    private static void check(String what, FlightSim.Flight f, double restTolerance) {
        assertEquals("landed", f.why(), what);
        assertTrue(Math.abs(f.restErr()) < restTolerance, what + ": rest " + f.restErr() + " off R");
        assertTrue(Math.abs(f.across()) < 1.5, what + ": " + f.across() + " across");
        assertTrue(f.touchVy() > -0.5, what + ": sink " + f.touchVy() + " at the touch-down");
        assertTrue(f.maxFall() <= 1.0 + 1e-9, what + ": fall distance " + f.maxFall());
        assertFalse(f.wall(), what + ": ran into a wall");
        assertTrue(f.rockets() >= 1 && f.rockets() <= CC.maxRockets(), what + ": rockets " + f.rockets());
    }

    @Test
    void takeOffSequenceJumpsReleasesJumpsAndFiresOnce() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 100.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        assertNotNull(r.plan());
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        FlightSim.Bot b = new FlightSim.Bot(w, 40.5, 172.875, 100.5);
        FlightControl.Keys k1 = c.tick(b.in());
        assertTrue(k1.jump() && !k1.rocket(), "jump on the ground");
        b.step(k1);
        FlightControl.Keys k2 = c.tick(b.in());
        assertFalse(k2.jump(), "let go in the air");
        b.step(k2);
        FlightControl.Keys k3 = c.tick(b.in());
        assertTrue(k3.jump(), "jump again in the air: the glide starts");
        b.step(k3);
        assertTrue(b.fallFlying);
        FlightControl.Keys k4 = c.tick(b.in());
        assertTrue(k4.rocket(), "the rocket once the glide is on");
        assertEquals(1, c.rocketsFired());
        b.step(k4);
        assertEquals(FlightControl.Phase.WAIT_BOOST, c.phase());
    }

    @Test
    void groundedStartsAbortSoTheWalkGoesOn() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 100.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        FlightControl.In ground = new FlightControl.In(40.5, 172.875, 100.5, 0, 0, 0, true, false, false, 10, 0f, true);
        assertTrue(c.tick(ground).jump());
        // the jump never left the ground (a ceiling, a cobweb): after 3 jumps of 4 ticks each the leg walks
        String why = null;
        int jumps = 1;
        for (int i = 0; i < 20 && why == null; i++) {
            FlightControl.Keys k = c.tick(ground);
            why = k.abort();
            if (k.jump()) jumps++;
        }
        assertEquals("the jump did not leave the ground (3 jumps)", why);
        assertEquals(FlightControl.JUMP_TRIES, jumps);
    }

    @Test
    void noGlideInTheAirBailsOutGently() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 100.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        c.tick(new FlightControl.In(40.5, 172.875, 100.5, 0, 0, 0, true, false, false, 10, 0f, true));
        c.tick(new FlightControl.In(40.5, 173.3, 100.5, 0, 0.3, 0, false, false, false, 10, 0f, true));
        c.tick(new FlightControl.In(40.5, 173.5, 100.5, 0, 0.2, 0, false, false, false, 10, 0f, true));
        // the server refused the glide (no START_ELYTRA_FLYING accepted): bail, never a rocket
        FlightControl.Keys k = c.tick(new FlightControl.In(40.5, 173.6, 100.5, 0, 0.1, 0, false, false, false, 10, 0f, true));
        assertFalse(k.rocket());
        assertEquals(FlightControl.Phase.BAIL, c.phase());
        assertEquals("the glide did not start", c.bailReason());
        // glide flag taken away mid-flight: bail, a plain fall to the ground (no rocket)
        FlightControl c2 = new FlightControl(r.plan().profile(), CC, 0f);
        FlightSim.Bot b = new FlightSim.Bot(w, 40.5, 172.875, 100.5);
        for (int i = 0; i < 30; i++) b.step(c2.tick(b.in()));
        assertEquals(FlightControl.Phase.FLY, c2.phase());
        b.fallFlying = false;
        FlightControl.Keys kb = c2.tick(b.in());
        assertEquals(FlightControl.GROUND_PITCH, kb.pitch());
        assertFalse(kb.rocket());
        assertEquals("the server ended the glide in the air", c2.bailReason());
        // the walk ended in the air: the bail-out lands on the floor, under the end rods, into no wall
        FlightSim lit = new FlightSim().lights();
        FlightPlanner.Result rl = FlightPlanner.plan(40.5, 172.875, 120.5, 40, 173, 10, lit.terrain(), FlightPlanner.Cfg.defaults(60));
        assertNotNull(rl.plan(), rl.refusal());
        FlightControl c3 = new FlightControl(rl.plan().profile(), CC, 0f);
        FlightSim.Bot b3 = new FlightSim.Bot(lit, 40.5, 172.875, 120.5);
        for (int i = 0; i < 25; i++) b3.step(c3.tick(b3.in()));
        assertEquals(FlightControl.Phase.FLY, c3.phase());
        c3.endInTheAir("the walk stopped in the air");
        int rockets = b3.rockets;
        FlightControl.Keys k3 = null;
        for (int i = 0; i < 200 && (k3 == null || !k3.done()); i++) {
            k3 = c3.tick(b3.in());
            if (!k3.done()) b3.step(k3);
        }
        assertTrue(k3.done(), "down and still");
        assertEquals("the walk stopped in the air", c3.bailReason());
        assertEquals(rockets, b3.rockets, "no rocket in a bail-out");
        assertFalse(b3.hitWall, "into no block");
        assertTrue(b3.maxFall <= 1.0 + 1e-9, "fall distance " + b3.maxFall);
    }

    @Test
    void noRocketUsableBailsAndASecondRocketOnlyWhenShort() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 100.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        c.tick(new FlightControl.In(40.5, 172.875, 100.5, 0, 0, 0, true, false, false, 10, 0f, true));
        c.tick(new FlightControl.In(40.5, 173.3, 100.5, 0, 0.3, 0, false, false, false, 10, 0f, true));
        c.tick(new FlightControl.In(40.5, 173.5, 100.5, 0, 0.2, 0, false, false, false, 10, 0f, true));
        FlightControl.Keys k = null;
        for (int i = 0; i < 6; i++) {
            k = c.tick(new FlightControl.In(40.5, 173.6, 100.5, 0, 0.0, 0, false, true, false, 10, 0f, false));
            assertFalse(k.rocket(), "a block in the look: never the right click");
        }
        assertEquals("no rocket could be used (not held, or a block in the look)", c.bailReason());
    }

    @Test
    void aDurationThreeRocketsBoostWindowIsFortyToFiftyOneTicks() {
        // vanilla lifetime 10 * (1 + d) + nextInt(6) + nextInt(7)
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 3);
        assertEquals(40, d3.boostTicks());
        assertEquals(51, d3.boostMax());
        assertEquals(90, FlightControl.minLegBlocks(3, 60));
        assertEquals(120, FlightControl.minLegBlocks(3, 120), "a longer configured minimum wins");
    }

    /**
     * The shortest duration-3 leg follows the longest boost expected: the sweep's rows,
     * interpolated and rounded up to 5, a walk past the last; the expected boost from vanilla's 51, the server's TPS and
     * the recent flights.
     */
    @Test
    void theShortestDurationThreeLegFollowsTheLongestBoostExpected() {
        assertEquals(90, FlightControl.minLegBlocks(3, 60, 51));
        assertEquals(100, FlightControl.minLegBlocks(3, 60, 55));
        assertEquals(105, FlightControl.minLegBlocks(3, 60, 58));
        assertEquals(115, FlightControl.minLegBlocks(3, 60, 66));
        assertEquals(Integer.MAX_VALUE, FlightControl.minLegBlocks(3, 60, 67), "longer boosts than simulated: walk");
        assertEquals(60, FlightControl.minLegBlocks(1, 60, 99), "duration 1 as since 5.25");
        assertEquals(56, FlightControl.worstBoost(3, Double.NaN, 0), "no boost seen yet: vanilla's 51 and 5");
        assertEquals(56, FlightControl.worstBoost(3, 20.0, 0));
        assertEquals(68, FlightControl.worstBoost(3, 15.0, 0), "51 server ticks at 15 TPS: 68 bot ticks");
        assertEquals(51, FlightControl.worstBoost(3, 20.0, 44), "the first live flight's 44: vanilla's 51 stands");
        assertEquals(59, FlightControl.worstBoost(3, 20.0, 54), "live 2026-10-25 08:33: 59 after 54 planned for");
        assertEquals(71, FlightControl.worstBoost(3, 20.0, 66), "local 10x: 66 seen");
        assertEquals(31, FlightControl.worstBoost(1, 10.0, 80), "duration 1 as since 5.25");
        FlightControl.BoostWatch w = new FlightControl.BoostWatch();
        assertEquals(0, w.recentMax(0));
        w.add(60, 0);
        for (int i = 0; i < FlightControl.BoostWatch.SIZE - 1; i++) w.add(44, 1000);
        assertEquals(60, w.recentMax(2000));
        w.add(45, 3000);
        assertEquals(45, w.recentMax(4000), "the oldest of SIZE fell out");
        assertEquals(0, w.recentMax(3000 + FlightControl.BoostWatch.MAX_AGE_MS + 1), "too old");
    }

    /**
     * Duration-3 rockets: from the shortest leg for the boost planned for (51 and 66 ticks)
     * to 25 blocks longer, with boosts from 40 ticks to that worst, the servers of the restock test and one rocket as
     * {@link FlightLeg} flies them (past {@link FlightControl#preflight}), every leg kind over the bare tile and over a
     * dense built art layer either walks or lands on the pad under the end rods: no contact with any block, at most 1.8
     * b/t, sinking under 0.5 b/t, no fall damage, at most {@link FlightControl#REST_PAST_MAX} past R and at most 12
     * short of it (the walk on). Most of them fly.
     */
    @Test
    void durationThreeLegsLandOnThePadWithEveryBoostUpToTheWorstPlannedFor() {
        int flown = 0;
        int total = 0;
        for (boolean art : new boolean[]{false, true}) {
            for (int worst : new int[]{51, 66}) {
                FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, worst);
                int min = FlightControl.minLegBlocks(3, 60, worst);
                for (int life : new int[]{40, worst}) {
                    for (int[] srv : SERVERS) {
                        for (int dist : new int[]{min, min + 10, min + 25}) {
                            String how = dist + (art ? " art" : " bare") + " worst " + worst + " life " + life + " delay "
                                + srv[0] + " flag " + srv[2];
                            FlightSim w = world(art);
                            double sz = Math.min(126.5, dist - 2.5);
                            flown += safeOrWalked("d3 out " + how, w.fly(20.5, feet(w, 20.5, sz), sz, 20, 174, -3,
                                FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
                            w = world(art);
                            flown += safeOrWalked("d3 back " + how, w.fly(40.5, 174, -2.5, 40, 173, dist - 3,
                                FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
                            w = world(art);
                            flown += safeOrWalked("d3 walkway " + how, w.fly(118.5, 174, -3.5, 118 - dist, 174, -2,
                                FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
                            double s30 = Math.sin(Math.toRadians(30));
                            double ox = 20.5 + dist * s30;
                            double oz = dist * Math.cos(Math.toRadians(30)) - 2.5;
                            w = world(art);
                            flown += safeOrWalked("d3 out diagonal " + how, w.fly(ox, feet(w, ox, oz), oz, 20, 174, -3,
                                FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
                            double s45 = Math.sin(Math.toRadians(45));
                            double dx = 0.5 + dist * s45;
                            double dz = dist * s45 - 1.5;
                            w = world(art);
                            flown += safeOrWalked("d3 drop point " + how, w.fly(dx, feet(w, dx, dz), dz, 0, 174, -2,
                                FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
                            total += 5;
                        }
                    }
                }
            }
        }
        assertTrue(flown * 2 >= total, flown + " of " + total + " flown: most legs past the first filter fly");
    }

    /** The soul sand model under the end rods; {@code art}: with a dense built art layer (candles and full blocks). */
    private static FlightSim world(boolean art) {
        FlightSim w = new FlightSim().lights().gated();
        return art ? w.art(40, 40) : w;
    }

    /**
     * Planning for the shortest boost only (as before 5.31), a 100-block leg with a 66-tick boost overshoots into the
     * sorter wall; planning for both it would land too far, so the pre-flight check walks it; 125 blocks fly.
     */
    @Test
    void aBoostLongerThanCountedOnNoLongerCarriesTheBotIntoTheWall() {
        FlightSim w = new FlightSim().lights().gated();
        FlightSim.Flight f = w.fly(20.5, 172.875, 97.5, 20, 174, -3, FlightPlanner.Cfg.defaults(50),
            FlightControl.Cfg.forDuration(3, 3, 1, 66), 2, 66, 2);
        assertTrue(f.why().startsWith("walked: no landing on the pad with boosts of 40 to 66 ticks"), f.why());
        w = new FlightSim().lights().gated();
        f = w.fly(20.5, 172.875, 122.5, 20, 174, -3, FlightPlanner.Cfg.defaults(50),
            FlightControl.Cfg.forDuration(3, 3, 1, 66), 2, 66, 2);
        safe("125 blocks, 66-tick boost", f, w);
    }

    /**
     * A walk's reason names what puts the best way off the pad when its rest point does not (the local run d3d
     * walked "1.1 short of R", on the pad), and each shortened R's outcome.
     */
    @Test
    void aWalksReasonNamesWhatPutsTheWayOffThePad() {
        assertNull(FlightControl.missWords(0));
        assertEquals("down off the pad", FlightControl.missWords(FlightControl.MISS_OFF_PAD));
        assertEquals("with the longest boost down while the rocket boosts",
            FlightControl.missWords(FlightControl.MISS_BOOSTED | FlightControl.MISS_WORST));
        assertEquals("at rest over 3 past R", FlightControl.missWords(FlightControl.MISS_PAST));
        assertEquals("with the longest boost near a roof",
            FlightControl.missWords(FlightControl.MISS_NEAR_ROOF | FlightControl.MISS_WORST));
        FlightControl.Choice c = new FlightControl.Choice(0f, FlightControl.OFF_PAD + 5, 1.1, 60, true,
            FlightControl.MISS_BOOSTED | FlightControl.MISS_WORST);
        assertEquals("the best comes to rest 1.1 short of R, with the longest boost down while the rocket boosts",
            FlightLeg.verdict(c));
        assertEquals("the pad's floor is not level", FlightLeg.padRefusal("no landing pad near the goal (the pad's floor is"
            + " not level at 1,173,2; candidates: 3 the pad's floor is not level)"));
        assertEquals("no landing pad within 90 blocks", FlightLeg.padRefusal("no landing pad within 90 blocks"));
        // a flight past one rocket's reach over dense art, no level pad back on the line: the reason says so
        FlightSim w = world(true);
        FlightSim.Flight f = w.fly(118.5, feet(w, 118.5, 125.5), 125.5, 20, 174, -3, FlightPlanner.Cfg.defaults(50),
            FlightControl.Cfg.forDuration(3, 3, 1, 52), 2, 52, 2);
        if (f.why().startsWith("walked: no landing on the pad") && f.why().contains("short of R")) {
            assertTrue(f.why().contains("; R moved back to "), f.why());
        }
    }

    /**
     * The local 10x run of 2026-10-25: a 102-block leg over the built art, planned for boosts up
     * to 52 ticks, found no pitch onto the pad for both boosts, cruised a block over the art and came down 30 blocks
     * short into a cobbled deepslate block. Over a dense art layer, legs of 85 blocks now walk (no landing for both
     * boosts) and legs of 100 land on the pad with either.
     */
    @Test
    void overBuiltArtALegWithNoLandingForTheLongestBoostWalks() {
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        FlightSim w = world(true);
        FlightSim.Flight f = w.fly(20.5, feet(w, 20.5, 82.5), 82.5, 20, 174, -3, FlightPlanner.Cfg.defaults(50), d3, 4, 52, 3);
        assertTrue(f.why().startsWith("walked: no landing on the pad with boosts of 40 to 52 ticks"), f.why());
        for (int life : new int[]{40, 46, 52}) {
            for (int[] srv : SERVERS) {
                w = world(true);
                double ox = 20.5 + 100 * Math.sin(Math.toRadians(30));
                double oz = 100 * Math.cos(Math.toRadians(30)) - 2.5;
                safe("100 blocks over the art, life " + life + " delay " + srv[0],
                    w.fly(ox, feet(w, ox, oz), oz, 20, 174, -3, FlightPlanner.Cfg.defaults(50), d3, srv[0], life, srv[2]), w);
            }
        }
    }

    /**
     * A leg longer than one duration-3 rocket glides under the end rods (162 blocks across the bare tile to the
     * walkway): R moves back along the line to where the look-ahead finds its landing, the flight lands on the pad there
     * with every boost, and the rest of the leg walks. Over dense art no level pad lies on the
     * line: it walks.
     */
    @Test
    void aLegLongerThanOneRocketReachesFliesItsReachableFirstPart() {
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        double leg = Math.hypot(20.5 - 118.5, -2.5 - 125.5);
        for (boolean art : new boolean[]{false, true}) {
            for (int life : new int[]{40, 52}) {
                for (int[] srv : SERVERS) {
                    FlightSim w = world(art);
                    String how = (art ? "art" : "bare") + " life " + life + " delay " + srv[0];
                    FlightSim.Flight f = w.fly(118.5, feet(w, 118.5, 125.5), 125.5, 20, 174, -3, FlightPlanner.Cfg.defaults(50),
                        d3, srv[0], life, srv[2]);
                    if (art) {
                        safeOrWalked("162 blocks, " + how, f, w);
                        continue;
                    }
                    safe("162 blocks, " + how, f, w);
                    assertFalse(w.lastRelay, how + ": one rocket");
                    assertTrue(w.lastPlan.flightBlocks() < leg - 30, how + ": flew " + w.lastPlan.flightBlocks() + " of " + leg);
                    assertTrue(w.lastPlan.flightBlocks() > 80, how + ": flew " + w.lastPlan.flightBlocks());
                }
            }
        }
    }

    /**
     * A flight past one duration-3 rocket's glide on an open field (250 blocks; one rocket's glide from the cruise
     * reaches about 220 without a roof): two rockets, the second where the look-ahead lands it on the pad with either
     * boost, with every boost of both and under rods every 9 x 16 blocks.
     */
    @Test
    void aFlightPastOneRocketsGlideFliesWithASecondRocket() {
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        for (boolean rods : new boolean[]{false, true}) {
            for (int life : new int[]{40, 52}) {
                for (int[] srv : SERVERS) {
                    FlightSim w = new FlightSim().open().gated();
                    if (rods) {
                        for (int z = -20; z < 280; z += 9) for (int x = -20; x < 40; x += 16) w.block(x, 178, z);
                    }
                    String how = (rods ? "rods" : "open") + " life " + life + " delay " + srv[0];
                    FlightSim.Flight f = w.fly(0.5, 172.875, 0.5, 0, 173, 250, FlightPlanner.Cfg.defaults(50), d3, srv[0], life,
                        srv[2]);
                    safe("250 blocks, " + how, f, w);
                    assertTrue(w.lastRelay, how + ": a relay flight");
                    assertEquals(2, f.rockets(), how + ": rockets");
                }
            }
        }
    }

    /**
     * {@code build.elytraHops}: a leg past one flight's reach flies on from where it landed, each
     * flight past the same checks. On an open field 300 blocks across rods (37 degrees) and 400 blocks without: at least
     * two flights, every one on its pad, the last within the shortest leg of the goal; the relay of the rods case with a
     * round trip of 8 (its second rocket landed 6.8 past R before {@link FlightControl#rocketLands}).
     */
    @Test
    void aLegPastOneFlightsReachFliesOnFromWhereItLanded() {
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        double minLeg = FlightControl.minLegBlocks(3, 50, 52);
        for (boolean rods : new boolean[]{true, false}) {
            int leg = rods ? 300 : 400;
            int ang = rods ? 37 : 0;
            int gx = (int) Math.round(leg * Math.sin(Math.toRadians(ang)));
            int gz = (int) Math.round(leg * Math.cos(Math.toRadians(ang)));
            for (int life : new int[]{40, 52}) {
                for (int[] srv : SERVERS) {
                    FlightSim w = new FlightSim().open().gated();
                    if (rods) {
                        for (int z = -40; z < 460; z += 9) for (int x = -40; x < 300; x += 16) w.block(x, 178, z);
                    }
                    String how = (rods ? "rods" : "open") + " " + leg + " life " + life + " delay " + srv[0];
                    List<FlightSim.Flight> fs = w.leg(0.5, 172.875, 0.5, gx, 173, gz, FlightPlanner.Cfg.defaults(50), d3,
                        srv[0], life, srv[2], 4, minLeg);
                    assertTrue(fs.size() >= 2, how + ": flights " + fs.size());
                    FlightSim.Flight last = null;
                    for (FlightSim.Flight f : fs) {
                        if (!f.why().equals("landed")) {
                            assertTrue(f.why().startsWith("walked: ") || f.why().startsWith("refused: "), how + ": " + f.why());
                            continue;
                        }
                        landedSafely(how, f);
                        last = f;
                    }
                    assertEquals(0, w.contactsAll, how + ": touched a block in the air");
                    assertNotNull(last, how);
                    double left = Math.hypot(gx + 0.5 - last.restX(), gz + 0.5 - last.restZ());
                    assertTrue(left < minLeg, how + ": " + left + " left after the last flight");
                }
            }
        }
    }

    /**
     * {@code build.elytraOnTheWayBlocks} (local run s3b): the walk from the map counter to the nuke
     * start walked, its take-off spot beside the counter's wall (1.4 along, 0.8 across, 10 blocks over the floor, over
     * the cruise limit). From 8 blocks on along the way the wall is behind the bot: the same leg plans, and each flight
     * to the goal lands on its pad.
     */
    @Test
    void aWallBesideTheTakeOffSpotFliesFromFurtherOnTheWay() {
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        double minLeg = FlightControl.minLegBlocks(3, 50, 52);
        for (int life : new int[]{40, 52}) {
            for (int[] srv : SERVERS) {
                FlightSim w = new FlightSim().open().gated();
                for (int z = -3; z <= 2; z++) for (int y = 173; y <= 182; y++) w.block(1, y, z);
                String how = "life " + life + " delay " + srv[0];
                FlightPlanner.Result atStart = FlightPlanner.plan(0.5, 172.875, 0.5, 0, 173, 300, w.terrain(),
                    FlightPlanner.Cfg.defaults(50));
                assertNull(atStart.plan(), how + ": the take-off beside the wall planned");
                assertTrue(atStart.refusal().startsWith("the flight path is blocked"), how + ": " + atStart.refusal());
                List<FlightSim.Flight> fs = w.leg(0.5, 172.875, 8.5, 0, 173, 300, FlightPlanner.Cfg.defaults(50), d3,
                    srv[0], life, srv[2], 4, minLeg);
                FlightSim.Flight last = null;
                for (FlightSim.Flight f : fs) {
                    if (!f.why().equals("landed")) {
                        assertTrue(f.why().startsWith("walked: ") || f.why().startsWith("refused: "), how + ": " + f.why());
                        continue;
                    }
                    landedSafely(how, f);
                    last = f;
                }
                assertEquals(0, w.contactsAll, how + ": touched a block in the air");
                assertNotNull(last, how + ": no flight from 8 blocks on");
                double left = Math.hypot(0.5 - last.restX(), 300.5 - last.restZ());
                assertTrue(left < minLeg, how + ": " + left + " left after the last flight");
            }
        }
    }

    /**
     * The soul sand tile with ground east of its east channel (x 128: water at y 170 on a glass floor at 169, the tile's
     * floor 2.875 above it): full blocks at y 172 for x 129-240, z 20-50, and with {@code rods} the platform's end rods
     * and the counter's line ({@link FlightSim#lights}).
     */
    private static FlightSim eastOfTheCanal(boolean rods) {
        FlightSim w = new FlightSim().gated();
        if (rods) w.lights();
        for (int x = 129; x <= 240; x++) for (int z = 20; z <= 50; z++) w.block(x, 172, z);
        return w;
    }

    /**
     * {@code build.elytraOverWater}: a 180-block flight from the ground east of the tile onto it
     * crosses the east channel. Off: it walks ("crosses water"). On: it flies, every flight on its pad, no block touched,
     * none in the water. Water within 8 blocks of the take-off still walks; a pond 6 wide on the line walks; a channel 3
     * wide flies.
     */
    @Test
    void aFlightOverTheCanalFliesOnlyWithItsSwitch() {
        FlightPlanner.Cfg off = FlightPlanner.Cfg.defaults(50);
        FlightPlanner.Cfg on = off.overWater(true);
        FlightPlanner.Result walked = FlightPlanner.plan(220.5, 173, 35.5, 40, 173, 35, eastOfTheCanal(false).terrain(), off);
        assertNull(walked.plan());
        assertTrue(walked.refusal().contains("crosses water"), walked.refusal());
        FlightPlanner.Result near = FlightPlanner.plan(133.5, 173, 35.5, 40, 173, 35, eastOfTheCanal(false).terrain(), on);
        assertNull(near.plan(), "water 5 blocks from the take-off");
        assertTrue(near.refusal().contains("crosses water"), near.refusal());
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        for (boolean rods : new boolean[]{false, true}) {
            for (int life : new int[]{40, 52}) {
                for (int[] srv : SERVERS) {
                    FlightSim w = eastOfTheCanal(rods);
                    String how = (rods ? "rods" : "open") + " life " + life + " delay " + srv[0];
                    FlightSim.Flight f = w.fly(220.5, 173, 35.5, 40, 173, 35, on, d3, srv[0], life, srv[2]);
                    assertEquals("landed", f.why(), how);
                    landedSafely(how, f);
                    assertEquals(0, w.lastContacts, how + ": touched a block in the air");
                    assertFalse(w.lastWet, how + ": came down in the water");
                    assertTrue(w.lastPlan.profile().water.size() > 0, how + ": the channel is not under the flight");
                }
            }
        }
        FlightSim pond = eastOfTheCanal(false);
        FlightSim channel = eastOfTheCanal(false);
        for (int z = 20; z <= 50; z++) {
            for (int x = 170; x <= 175; x++) {
                pond.blocks.remove(x + ",172," + z);
                pond.block(x, 171, z);
                pond.water.add(x + ",172," + z);
            }
            for (int x = 170; x <= 172; x++) {
                channel.blocks.remove(x + ",172," + z);
                channel.block(x, 171, z);
                channel.water.add(x + ",172," + z);
            }
        }
        FlightPlanner.Result wide = FlightPlanner.plan(220.5, 173, 35.5, 40, 173, 35, pond.terrain(), on);
        assertNull(wide.plan(), "a pond 6 wide");
        assertTrue(wide.refusal().contains("blocks of water in a row"), wide.refusal());
        assertNotNull(FlightPlanner.plan(220.5, 173, 35.5, 40, 173, 35, channel.terrain(), on).plan(), "a channel 3 wide");
    }

    /**
     * {@code build.elytraOverWater}, a flight that goes wrong over the east channel: a boost that
     * ends early (5 to 30 ticks against the 40-52 planned for) and a bail-out forced at every third tick of the flight
     * (a walk stopped in the air: {@link FlightControl#endInTheAir}). Where each one comes down, by place: the look-ahead
     * never comes down on water, so none ends in the channel; a fall into it would be the fall rescue's (its climb out
     * of the soul sand channels).
     */
    @Test
    void aFlightThatGoesWrongOverTheCanalComesDownOutOfTheWater() {
        FlightPlanner.Cfg on = FlightPlanner.Cfg.defaults(50).overWater(true);
        FlightControl.Cfg d3 = FlightControl.Cfg.forDuration(3, 3, 1, 52);
        int[] where = new int[4]; // the tile, the channel, the ground east of it, elsewhere
        int cases = 0;
        int touched = 0;
        int walls = 0;
        double worstFall = 0;
        for (boolean rods : new boolean[]{false, true}) {
            for (int[] srv : SERVERS) {
                List<int[]> tries = new java.util.ArrayList<>(); // life, bail tick
                for (int life : new int[]{5, 10, 20, 30}) tries.add(new int[]{life, -1});
                FlightSim probe = eastOfTheCanal(rods);
                int ticks = probe.fly(220.5, 173, 35.5, 40, 173, 35, on, d3, srv[0], 46, srv[2]).ticks();
                for (int t = 1; t <= ticks; t += 3) tries.add(new int[]{46, t});
                for (int[] tr : tries) {
                    FlightSim w = eastOfTheCanal(rods);
                    w.bailAt = tr[1];
                    String how = (rods ? "rods" : "open") + " delay " + srv[0] + " life " + tr[0] + " bail at " + tr[1];
                    FlightSim.Flight f = w.fly(220.5, 173, 35.5, 40, 173, 35, on, d3, srv[0], tr[0], srv[2]);
                    assertFalse(f.why().startsWith("refused") || f.why().startsWith("walked"), how + ": " + f.why());
                    cases++;
                    double x = f.restX();
                    int place = w.lastWet || (x >= 128 && x < 129) ? 1 : x < 128 ? 0 : x <= 241 ? 2 : 3;
                    where[place]++;
                    if (w.lastContacts > 0) touched++;
                    if (f.wall()) walls++;
                    worstFall = Math.max(worstFall, f.maxFall());
                    assertFalse(w.lastWet, how + ": came down in the water at y " + w.lastY);
                    assertTrue(place != 1, how + ": came down in the channel at x " + x);
                }
            }
        }
        System.out.printf("over the canal, gone wrong: %d cases; down on the tile %d, in the channel %d, east of it %d,"
            + " elsewhere %d; touched a block in the air %d, into a wall %d, worst fall %.2f%n", cases, where[0], where[1],
            where[2], where[3], touched, walls, worstFall);
        assertEquals(0, where[3], "came down off the tile and the ground");
    }

    /** Which walks may fly ({@link FlightLeg#flies}): restock legs in the restock; others only with their switch. */
    @Test
    void aWalkOutsideTheRestockFliesOnlyWithItsKindsSwitch() {
        io.github.digitalisp.mapart.config.MapArtConfig.BuildCfg b = new io.github.digitalisp.mapart.config.MapArtConfig.BuildCfg();
        assertEquals(0, b.elytraHops, "further flights off by default");
        assertEquals(0, b.elytraOnTheWayBlocks, "planning again on the way off by default");
        assertTrue(FlightLeg.flies(b, SprintLegs.Walk.CONTAINER, true));
        assertTrue(FlightLeg.flies(b, SprintLegs.Walk.DROP_POINT, true));
        assertTrue(FlightLeg.flies(b, SprintLegs.Walk.LANE_START, true));
        for (SprintLegs.Walk k : SprintLegs.Walk.values()) {
            assertFalse(FlightLeg.flies(b, k, false), k + " outside the restock with every switch off");
        }
        assertFalse(FlightLeg.flies(b, SprintLegs.Walk.WALK, true));
        b.elytraWalkLegs = true;
        b.elytraContainerLegs = true;
        b.elytraLaneStartLegs = true;
        b.elytraStandSpotLegs = true;
        b.elytraMapStationLegs = true;
        for (SprintLegs.Walk k : SprintLegs.Walk.values()) {
            boolean on = switch (k) {
                case WALK, CONTAINER, LANE_START, STAND_SPOT, MAP_STATION -> true;
                default -> false;
            };
            assertEquals(on, FlightLeg.flies(b, k, false), k + " outside the restock with every switch on");
        }
        for (SprintLegs.Walk k : new SprintLegs.Walk[]{SprintLegs.Walk.ESCAPE, SprintLegs.Walk.RETREAT,
            SprintLegs.Walk.CHASE, SprintLegs.Walk.BREAK_TRANSIT, SprintLegs.Walk.BUTTON}) {
            assertFalse(FlightLeg.flies(b, k, true), k + " never flies");
        }
    }

    /** {@link #safe}'s landing rules for one flight of a leg (contacts: {@link FlightSim#contactsAll}). */
    private static void landedSafely(String what, FlightSim.Flight f) {
        assertTrue(f.restErr() <= FlightControl.REST_PAST_MAX && f.restErr() >= -12, what + ": rest " + f.restErr() + " off R");
        assertTrue(Math.abs(f.across()) < 1.5, what + ": " + f.across() + " across");
        assertTrue(f.touchSpeed() <= 1.8, what + ": " + f.touchSpeed() + " b/t at the touch-down");
        assertTrue(f.touchVy() > -0.5, what + ": sink " + f.touchVy() + " at the touch-down");
        assertTrue(f.maxFall() <= 1.0 + 1e-9, what + ": fall distance " + f.maxFall());
        assertFalse(f.wall(), what + ": ran into a wall");
        assertTrue(f.rockets() == 1 || f.rockets() == 2, what + ": rockets " + f.rockets());
    }

    /** {@link #safe}, or walked (the planner or the pre-flight check refused it): 1 when it flew. */
    private static int safeOrWalked(String what, FlightSim.Flight f, FlightSim w) {
        if (f.why().startsWith("walked: ") || f.why().startsWith("refused: ")) return 0;
        safe(what, f, w);
        return 1;
    }

    /** {@link #durationThreeLegsLandOnThePadWithEveryBoostUpToTheWorstPlannedFor}'s landing rules. */
    private static void safe(String what, FlightSim.Flight f, FlightSim w) {
        assertEquals("landed", f.why(), what);
        assertTrue(f.restErr() <= FlightControl.REST_PAST_MAX && f.restErr() >= -12, what + ": rest " + f.restErr() + " off R");
        assertTrue(Math.abs(f.across()) < 1.5, what + ": " + f.across() + " across");
        assertTrue(f.touchSpeed() <= 1.8, what + ": " + f.touchSpeed() + " b/t at the touch-down");
        assertTrue(f.touchVy() > -0.5, what + ": sink " + f.touchVy() + " at the touch-down");
        assertTrue(f.maxFall() <= 1.0 + 1e-9, what + ": fall distance " + f.maxFall());
        assertFalse(f.wall(), what + ": ran into a wall");
        assertEquals(0, w.lastContacts, what + ": touched a block in the air");
        assertTrue(f.rockets() == 1 || w.lastRelay && f.rockets() == 2, what + ": rockets " + f.rockets());
    }

    /**
     * The overshoot gate: near the pad at cruise speed, a duration-3 rocket (worst case 51 ticks) would carry the bot
     * past it, so none goes; far from the pad it may.
     */
    @Test
    void aRocketWhoseLongestBoostWouldOvershootThePadDoesNotGo() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 120.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        assertNotNull(r.plan());
        FlightPlanner.Profile p = r.plan().profile();
        FlightControl d3 = new FlightControl(p, FlightControl.Cfg.forDuration(3, 3, 3), 0f);
        // 20 blocks before R, 3 over the floor, flying north at 1 b/t: the longest boost lands past R or too fast
        ElytraPhysics.State near = new ElytraPhysics.State(40.5, 176.0, 10.5 + 20, 0, 0, -1.0);
        assertTrue(d3.overshoots(near, 180f), "20 blocks short at 1 b/t: a 51-tick boost overshoots");
        // 80 blocks before R, low and slow: it lands on the pad
        ElytraPhysics.State far = new ElytraPhysics.State(40.5, 174.5, 10.5 + 80, 0, 0, -0.6);
        assertFalse(d3.overshoots(far, 180f), "80 blocks short: a boost lands on the pad");
    }

    @Test
    void lookAheadNeverChoosesAPitchIntoTheCeilingOrOffTheCorridor() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = FlightPlanner.plan(40.5, 172.875, 120.5, 40, 173, 10, w.terrain(), FlightPlanner.Cfg.defaults(60));
        FlightControl c = new FlightControl(r.plan().profile(), CC, 0f);
        // fast and level at the ceiling's edge with a rocket boosting: climbing pitches hit the ceiling
        ElytraPhysics.State s = new ElytraPhysics.State(40.5, r.plan().profile().ceiling - 1.0, 110.5, 0, 0.2, -1.6);
        FlightControl.Choice best = c.choose(s, 180f, 0, 20, 0f);
        assertTrue(best.score() < FlightControl.INVALID, "some pitch is valid");
        assertTrue(best.pitch() >= -10, "pitch " + best.pitch() + " would climb into the ceiling");
    }
}
