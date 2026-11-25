package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The double-break planner: which blocks pair, which one is delayed. Speeds are vanilla destroy progress per tick
 * (tool speed / hardness / 30 with the right tool): an Efficiency V netherite tool is 9 + 5² + 1 = 35, a plain diamond
 * pickaxe 8, the hand 1.
 */
class BreakPairsTest {
    private static final String PICK = "pickaxe";
    private static final String AXE = "axe";
    private static final String HAND = ToolRack.HAND;

    // Efficiency V netherite pickaxe
    /** 0.933: 2 ticks, too fast to delay (the early STOP would break it), may be the active block. */
    private static final BreakPairs.Info TERRACOTTA = new BreakPairs.Info(PICK, 35 / 1.25 / 30);
    /** 0.648: 2 ticks, may be delayed. */
    private static final BreakPairs.Info CONCRETE = new BreakPairs.Info(PICK, 35 / 1.8 / 30);
    /** 0.778: 2 ticks, active only. */
    private static final BreakPairs.Info STONE = new BreakPairs.Info(PICK, 35 / 1.5 / 30);
    // plain diamond pickaxe
    /** 0.213: 5 ticks. */
    private static final BreakPairs.Info TERRACOTTA_DIA = new BreakPairs.Info(PICK, 8 / 1.25 / 30);
    /** 0.148: 7 ticks. */
    private static final BreakPairs.Info CONCRETE_DIA = new BreakPairs.Info(PICK, 8 / 1.8 / 30);
    /** 0.178: 6 ticks. */
    private static final BreakPairs.Info STONE_DIA = new BreakPairs.Info(PICK, 8 / 1.5 / 30);
    // by hand
    /** 0.0417: 24 ticks. */
    private static final BreakPairs.Info WOOL = new BreakPairs.Info(HAND, 1 / 0.8 / 30);
    /** 0.333: 3 ticks. */
    private static final BreakPairs.Info CARPET = new BreakPairs.Info(HAND, 1 / 0.1 / 30);
    // Efficiency V netherite axe
    /** 5.83: breaks on the START. */
    private static final BreakPairs.Info STEM = new BreakPairs.Info(AXE, 35 / 0.2 / 30);
    /** 0.583: 2 ticks, may be delayed. */
    private static final BreakPairs.Info LOG = new BreakPairs.Info(AXE, 35 / 2.0 / 30);

    private static BreakPairs.Info info(String group, double speed) {
        return new BreakPairs.Info(group, speed);
    }

    // ---- Info ----

    @Test
    void realisticSpeeds() {
        assertEquals(2, TERRACOTTA.ticks());
        assertFalse(TERRACOTTA.canDelay(), "0.933 >= 0.7: the STOP in the START tick would break it");
        assertTrue(TERRACOTTA.canBeActive());
        assertFalse(TERRACOTTA.instant());

        assertEquals(2, CONCRETE.ticks());
        assertTrue(CONCRETE.canDelay());
        assertTrue(CONCRETE.canBeActive());

        assertEquals(2, STONE.ticks());
        assertFalse(STONE.canDelay());
        assertTrue(STONE.canBeActive());

        assertEquals(5, TERRACOTTA_DIA.ticks());
        assertEquals(7, CONCRETE_DIA.ticks());
        assertEquals(6, STONE_DIA.ticks());
        assertTrue(TERRACOTTA_DIA.canDelay());

        assertEquals(24, WOOL.ticks());
        assertTrue(WOOL.canDelay());
        assertEquals(3, CARPET.ticks());
        assertTrue(CARPET.canDelay());

        assertTrue(STEM.instant());
        assertEquals(1, STEM.ticks());
        assertFalse(STEM.canDelay());
        assertFalse(STEM.canBeActive());
        assertTrue(LOG.canDelay());
    }

    @Test
    void edgeSpeeds() {
        BreakPairs.Info atLimit = info(PICK, 0.7);
        assertEquals(0.7, BreakPairs.DELAY_MAX_SPEED);
        assertFalse(atLimit.canDelay(), "vanilla breaks at progress >= 0.7");
        assertTrue(atLimit.canBeActive());
        assertFalse(atLimit.instant());

        BreakPairs.Info below = info(PICK, 0.6999);
        assertTrue(below.canDelay());
        assertTrue(below.canBeActive());

        BreakPairs.Info one = info(PICK, 1.0);
        assertTrue(one.instant());
        assertFalse(one.canBeActive());
        assertFalse(one.canDelay());
        assertEquals(1, one.ticks());

        BreakPairs.Info almost = info(PICK, 0.9999);
        assertFalse(almost.instant());
        assertTrue(almost.canBeActive());
        assertFalse(almost.canDelay());

        for (double s : new double[] {0, -1, Double.NaN}) {
            BreakPairs.Info never = info(PICK, s);
            assertFalse(never.canDelay(), "speed " + s);
            assertFalse(never.canBeActive(), "speed " + s);
            assertFalse(never.instant(), "speed " + s);
        }
    }

    @Test
    void noGroupIsNeverPaired() {
        BreakPairs.Info none = info("", 0.3);
        assertFalse(none.canDelay());
        assertFalse(none.canBeActive());
        assertFalse(info("", 0.9).canBeActive());
    }

    @Test
    void ticksAreToolChoiceTicks() {
        for (double s : new double[] {0, 0.0417, 0.1, 0.148, 0.2, 0.213, 0.25, 0.333, 0.5, 0.6999, 0.7, 0.933, 0.9999,
            1.0, 5.83}) {
            assertEquals(ToolChoice.ticks(s), info(PICK, s).ticks(), "speed " + s);
        }
    }

    // ---- pairable ----

    @Test
    void pairableNeedsOneDelayableBlockAndTheSameGroup() {
        assertTrue(BreakPairs.pairable(CONCRETE, TERRACOTTA));
        assertTrue(BreakPairs.pairable(TERRACOTTA, CONCRETE));
        assertTrue(BreakPairs.pairable(CONCRETE, CONCRETE));
        assertTrue(BreakPairs.pairable(CONCRETE, STONE));
        assertTrue(BreakPairs.pairable(TERRACOTTA_DIA, STONE_DIA));
        assertTrue(BreakPairs.pairable(WOOL, CARPET));
        assertTrue(BreakPairs.pairable(WOOL, WOOL));
        assertTrue(BreakPairs.pairable(LOG, LOG));
    }

    @Test
    void differentGroupsNeverPair() {
        assertFalse(BreakPairs.pairable(CONCRETE, LOG), "pickaxe vs axe, both delayable");
        assertFalse(BreakPairs.pairable(LOG, CONCRETE));
        assertFalse(BreakPairs.pairable(CONCRETE, WOOL), "pickaxe vs hand");
        assertFalse(BreakPairs.pairable(WOOL, TERRACOTTA_DIA));
        assertFalse(BreakPairs.pairable(info("", 0.3), info("", 0.3)), "no group");
        assertFalse(BreakPairs.pairable(info("", 0.3), CONCRETE));
        assertFalse(BreakPairs.pairable(CONCRETE, info("", 0.3)));
    }

    @Test
    void twoFastBlocksNeverPair() {
        assertFalse(BreakPairs.pairable(TERRACOTTA, STONE), "0.933 and 0.778: neither can be delayed");
        assertFalse(BreakPairs.pairable(TERRACOTTA, TERRACOTTA));
        assertFalse(BreakPairs.pairable(info(PICK, 0.7), info(PICK, 0.7)));
        assertTrue(BreakPairs.pairable(info(PICK, 0.6999), info(PICK, 0.7)));
    }

    @Test
    void anInstantBlockNeverPairs() {
        assertFalse(BreakPairs.pairable(STEM, LOG));
        assertFalse(BreakPairs.pairable(LOG, STEM));
        assertFalse(BreakPairs.pairable(STEM, STEM));
        assertFalse(BreakPairs.pairable(info(PICK, 1.0), CONCRETE));
        assertFalse(BreakPairs.pairable(CONCRETE, info(PICK, 1.0)));
    }

    // ---- partners ----

    @Test
    void partnersBySimilarTimeThenNearestThenInputOrder() {
        Map<String, BreakPairs.Info> infos = new HashMap<>();
        Map<String, Double> dist = new HashMap<>();
        infos.put("t", TERRACOTTA_DIA); // 5 ticks
        put(infos, dist, "concreteNear", CONCRETE_DIA, 1); // gap 2
        put(infos, dist, "stoneFar", STONE_DIA, 9); // gap 1
        put(infos, dist, "stoneNear", STONE_DIA, 1); // gap 1
        put(infos, dist, "terraA", TERRACOTTA_DIA, 4); // gap 0
        put(infos, dist, "terraB", TERRACOTTA_DIA, 4); // gap 0, same distance: input order
        put(infos, dist, "wool", WOOL, 1);
        put(infos, dist, "stem", STEM, 1);
        put(infos, dist, "log", LOG, 1);
        put(infos, dist, "none", info("", 0.2), 1);
        dist.put("unknown", 1.0); // no Info: unloaded, air, refused or worn

        List<String> in = List.of("wool", "concreteNear", "stoneFar", "t", "terraA", "unknown", "stem", "stoneNear",
            "log", "none", "terraB");
        List<String> out = BreakPairs.partners("t", TERRACOTTA_DIA, in, infos::get, dist::get);
        assertEquals(List.of("terraA", "terraB", "stoneNear", "stoneFar", "concreteNear"), out);

        List<String> swapped = BreakPairs.partners("t", TERRACOTTA_DIA, List.of("terraB", "terraA"), infos::get,
            dist::get);
        assertEquals(List.of("terraB", "terraA"), swapped, "a full tie keeps the given order");
    }

    @Test
    void partnersAreOnlyPairableOnes() {
        Map<String, BreakPairs.Info> infos = Map.of("stone", STONE, "terra", TERRACOTTA, "concrete", CONCRETE,
            "wool", WOOL);
        List<String> out = BreakPairs.partners("t", TERRACOTTA, List.of("stone", "terra", "concrete", "wool"),
            infos::get, c -> 1.0);
        assertEquals(List.of("concrete"), out, "terracotta (active only) pairs with concrete only");

        assertEquals(List.of(), BreakPairs.partners("t", TERRACOTTA, List.of("stone", "terra"), infos::get, c -> 1.0));
        assertEquals(List.of(), BreakPairs.partners("t", TERRACOTTA, List.of(), infos::get, c -> 1.0));
    }

    @Test
    void firstItselfIsSkipped() {
        Map<String, BreakPairs.Info> infos = Map.of("c", CONCRETE, "d", CONCRETE);
        assertEquals(List.of("d"), BreakPairs.partners("c", CONCRETE, List.of("c", "d"), infos::get, c -> 1.0));
        assertEquals(List.of(), BreakPairs.partners("c", CONCRETE, List.of("c"), infos::get, c -> 1.0));
    }

    @Test
    void noPartnersWhenFirstCannotBeActive() {
        Map<String, BreakPairs.Info> infos = Map.of("log", LOG, "stem2", STEM);
        assertEquals(List.of(), BreakPairs.partners("stem", STEM, List.of("log", "stem2"), infos::get, c -> 1.0),
            "an instant block breaks on the START: nothing to gain");
        Map<String, BreakPairs.Info> none = Map.of("x", info("", 0.3));
        assertEquals(List.of(), BreakPairs.partners("n", info("", 0.3), List.of("x"), none::get, c -> 1.0));
        Map<String, BreakPairs.Info> zero = Map.of("c", CONCRETE);
        assertEquals(List.of(), BreakPairs.partners("z", info(PICK, 0), List.of("c"), zero::get, c -> 1.0));
    }

    private static void put(Map<String, BreakPairs.Info> infos, Map<String, Double> dist, String k, BreakPairs.Info i,
                            double d) {
        infos.put(k, i);
        dist.put(k, d);
    }

    // ---- round ----

    @Test
    void theSlowerBlockIsDelayed() {
        BreakPairs.Round<String> r = BreakPairs.round("c", CONCRETE, "t", TERRACOTTA);
        assertTrue(r.pair());
        assertEquals("c", r.delayed());
        assertEquals("t", r.active());
        assertEquals("c", r.first(), "a pair starts with the delayed block");
    }

    @Test
    void theSlowerPartnerIsDelayed() {
        BreakPairs.Round<String> r = BreakPairs.round("t", TERRACOTTA, "c", CONCRETE);
        assertTrue(r.pair());
        assertEquals("c", r.delayed());
        assertEquals("t", r.active());
        assertEquals("c", r.first());

        BreakPairs.Round<String> dia = BreakPairs.round("terra", TERRACOTTA_DIA, "stone", STONE_DIA);
        assertEquals(new BreakPairs.Round<>("stone", "terra"), dia, "0.178 is slower than 0.213");

        BreakPairs.Round<String> hand = BreakPairs.round("carpet", CARPET, "wool", WOOL);
        assertEquals(new BreakPairs.Round<>("wool", "carpet"), hand);
    }

    @Test
    void onATieFirstIsDelayed() {
        assertEquals(new BreakPairs.Round<>("a", "b"), BreakPairs.round("a", CONCRETE, "b", CONCRETE));
        assertEquals(new BreakPairs.Round<>("b", "a"), BreakPairs.round("b", CONCRETE, "a", CONCRETE));
        assertEquals(new BreakPairs.Round<>("w1", "w2"), BreakPairs.round("w1", WOOL, "w2", WOOL));
    }

    @Test
    void anUnpairablePartnerMakesASingle() {
        assertSingle("t", BreakPairs.round("t", TERRACOTTA, "s", STONE));
        assertSingle("t", BreakPairs.round("t", TERRACOTTA, "t2", TERRACOTTA));
        assertSingle("c", BreakPairs.round("c", CONCRETE, "l", LOG));
        assertSingle("c", BreakPairs.round("c", CONCRETE, "w", WOOL));
        assertSingle("stem", BreakPairs.round("stem", STEM, "l", LOG));
        assertSingle("l", BreakPairs.round("l", LOG, "stem", STEM));
        assertSingle("c", BreakPairs.round("c", CONCRETE, null, null));
        assertSingle("c", BreakPairs.round("c", CONCRETE, "d", null));
        assertSingle("c", BreakPairs.round("c", CONCRETE, null, CONCRETE));
        assertSingle("c", BreakPairs.round("c", CONCRETE, "n", info("", 0.3)));
    }

    private static void assertSingle(String first, BreakPairs.Round<String> r) {
        assertFalse(r.pair());
        assertNull(r.delayed());
        assertEquals(first, r.active());
        assertEquals(first, r.first(), "a single starts with its only block");
    }

    // ---- reach: what the caller (BreakRun) passes ----

    private static final double REACH = new MapArtConfig.BuildCfg().reach;
    private static final int FLOOR = 64;

    /** Feet on top of the art layer at column (x, z), eye {@link Aim#EYE_HEIGHT} above. */
    private record Stand(double x, double y, double z) {
        static Stand on(int x, int z) {
            return new Stand(x + 0.5, FLOOR + 1 + Aim.EYE_HEIGHT, z + 0.5);
        }

        double distSq(Pos p) {
            return p.distSq(x, y, z);
        }
    }

    private static Pos at(int x, int z) {
        return new Pos(x, FLOOR, z);
    }

    /** Partners of {@code first} as BreakRun asks for them: open targets with eye → centre within reach + 0.9. */
    private static List<Pos> partnersFrom(Stand eye, Pos first, Map<Pos, BreakPairs.Info> grid) {
        double r = REACH + 0.9;
        List<Pos> near = new ArrayList<>();
        for (Pos c : grid.keySet()) {
            if (!c.equals(first) && eye.distSq(c) <= r * r) near.add(c);
        }
        return BreakPairs.partners(first, grid.get(first), near, grid::get,
            c -> c.distSq(first.x() + 0.5, first.y() + 0.5, first.z() + 0.5));
    }

    @Test
    void reachFilterOfTheCallerFollowsTheEye() {
        assertEquals(4.5, REACH);
        Stand eye = Stand.on(0, 0);
        double r2 = (REACH + 0.9) * (REACH + 0.9);
        assertTrue(eye.distSq(at(4, 0)) <= r2);
        assertTrue(eye.distSq(at(4, 2)) <= r2);
        assertTrue(eye.distSq(at(3, 3)) <= r2);
        assertFalse(eye.distSq(at(5, 0)) <= r2, "5 columns out: 5.43 > 5.4");
        assertFalse(eye.distSq(at(4, 3)) <= r2);
    }

    @Test
    void netheritePairsOnlyAmongSameToolBlocksInReach() {
        Map<Pos, BreakPairs.Info> grid = new LinkedHashMap<>();
        Pos first = at(4, 0);
        grid.put(first, TERRACOTTA);
        grid.put(at(5, 0), CONCRETE); // next to first, out of reach from (0, 0)
        grid.put(at(4, 1), STONE); // same tool, but both too fast to delay
        grid.put(at(3, 0), WOOL); // by hand
        grid.put(at(4, -1), STEM); // axe, instant
        grid.put(at(3, -1), LOG); // axe
        grid.put(at(-3, 0), CONCRETE); // in reach, far from first
        grid.put(at(3, 2), CONCRETE); // in reach, distSq 5 from first
        grid.put(at(2, 0), CONCRETE); // in reach, distSq 4 from first

        Stand eye = Stand.on(0, 0);
        List<Pos> p = partnersFrom(eye, first, grid);
        assertEquals(List.of(at(2, 0), at(3, 2), at(-3, 0)), p, "all 2 ticks: nearest to first first");
        BreakPairs.Round<Pos> r = BreakPairs.round(first, TERRACOTTA, p.getFirst(), grid.get(p.getFirst()));
        assertEquals(new BreakPairs.Round<>(at(2, 0), first), r, "concrete delayed, terracotta mined");

        // standing over first, the neighbour comes into reach and is the nearest
        assertEquals(at(5, 0), partnersFrom(Stand.on(4, 0), first, grid).getFirst());

        // whatever is first: a partner shares its tool, lies in reach and pairs; instants and stone get none
        double r2 = (REACH + 0.9) * (REACH + 0.9);
        for (Pos f : grid.keySet()) {
            for (Pos c : partnersFrom(eye, f, grid)) {
                assertEquals(grid.get(f).group(), grid.get(c).group(), f + " / " + c);
                assertTrue(eye.distSq(c) <= r2, c + " in reach");
                assertTrue(BreakPairs.pairable(grid.get(f), grid.get(c)));
            }
        }
        assertEquals(List.of(), partnersFrom(eye, at(4, -1), grid), "stem");
        assertEquals(List.of(), partnersFrom(eye, at(3, 0), grid), "the only hand block");
        assertEquals(List.of(), partnersFrom(eye, at(3, -1), grid), "the log: the other axe block is instant");
        assertEquals(List.of(at(3, 2), at(2, 0), at(-3, 0)), partnersFrom(eye, at(4, 1), grid),
            "stone pairs with concrete only (distSq 2, 5, 50)");
    }

    @Test
    void theMostSimilarBreakTimeWinsOverTheNearest() {
        Map<Pos, BreakPairs.Info> grid = new LinkedHashMap<>();
        Pos first = at(4, 2);
        grid.put(first, TERRACOTTA_DIA); // 5 ticks
        grid.put(at(5, 2), TERRACOTTA_DIA); // 5 ticks, next to first, out of reach
        grid.put(at(4, 1), CONCRETE_DIA); // 7 ticks, distSq 1 from first
        grid.put(at(3, 2), STONE_DIA); // 6 ticks, distSq 1
        grid.put(at(-2, -3), TERRACOTTA_DIA); // 5 ticks, distSq 61
        grid.put(at(3, 3), WOOL); // 24 ticks, by hand
        grid.put(at(-1, 2), WOOL); // 24 ticks
        grid.put(at(2, 3), CARPET); // 3 ticks
        grid.put(at(0, 4), STEM);

        Stand eye = Stand.on(0, 0);
        List<Pos> p = partnersFrom(eye, first, grid);
        assertEquals(List.of(at(-2, -3), at(3, 2), at(4, 1)), p, "tick gap 0, 1, 2 over distance");
        assertEquals(new BreakPairs.Round<>(first, at(-2, -3)),
            BreakPairs.round(first, TERRACOTTA_DIA, p.getFirst(), grid.get(p.getFirst())), "same speed: first delayed");
        assertEquals(new BreakPairs.Round<>(at(3, 2), first),
            BreakPairs.round(first, TERRACOTTA_DIA, at(3, 2), STONE_DIA), "the slower stone is delayed");

        assertEquals(List.of(at(-1, 2), at(2, 3)), partnersFrom(eye, at(3, 3), grid),
            "wool: the other wool (gap 0) before the nearer carpet (gap 21)");
        assertEquals(List.of(at(3, 3), at(-1, 2)), partnersFrom(eye, at(2, 3), grid),
            "carpet: both wools gap 21, nearest first");
    }

    // ---- singleTicks ----

    @Test
    void singleTicks() {
        assertEquals(1, BreakPairs.singleTicks(STEM));
        assertEquals(1, BreakPairs.singleTicks(info(PICK, 1.0)));
        assertEquals(2 + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(TERRACOTTA));
        assertEquals(2 + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(CONCRETE));
        assertEquals(5 + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(TERRACOTTA_DIA));
        assertEquals(24 + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(WOOL));
        assertEquals(3 + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(CARPET));
        for (BreakPairs.Info i : List.of(TERRACOTTA, STONE, CONCRETE_DIA, STONE_DIA, LOG, info(PICK, 0.7))) {
            assertEquals(i.ticks() + DigClient.DESTROY_DELAY + 1, BreakPairs.singleTicks(i), i.toString());
        }
    }
}
