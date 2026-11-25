package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-reach greedy ({@code build.placeInReachGreedy}, {@link LaneRun} "In-reach greedy") on the simulated platform
 * ({@link LaneSim}: acks 5 ticks late, the Baritone stand-in moving the bot along its route): cells of the next lane go
 * up on the walk to it and from the walk past it, never a full block on or beside a later lane's walking line or on
 * Baritone's way, never an item the load cannot spare, never from the main inventory, never a block that would hide a
 * later lane's LOOK cell; every cell is placed once and reported once; off, nothing changes.
 */
class InReachGreedyTest {
    private static final int ART_Y = 64;
    private static final String WHITE = "minecraft:white_carpet";
    private static final String BLACK = "minecraft:black_carpet";
    private static final String TERRACOTTA = "minecraft:white_terracotta";
    private static final String DRIPSTONE = "minecraft:pointed_dripstone";

    /** A platform, its stock and what the lanes reported. */
    private static final class Bed {
        final Map<Pos, BlockSpec> world = new HashMap<>();
        final Map<String, Integer> stock = new HashMap<>();
        final Map<Pos, Integer> accepted = new HashMap<>();
        final Map<Pos, Integer> reported = new HashMap<>();
        /** Placements accepted while the Baritone stand-in walked, with the bot's feet cell then. */
        final List<String> whileWalking = new ArrayList<>();
        final Map<Pos, Pos> walkingFeet = new HashMap<>();
        final LaneSim sim;
        final PlacementListener listener = new PlacementListener() {
            @Override
            public void placed(Placement p) {
                reported.merge(p.pos(), 1, Integer::sum);
            }

            @Override
            public void skipped(Placement p, String reason) { }
        };

        Bed(long seed) {
            sim = new LaneSim(new LaneSim.Blocks() {
                @Override
                public BlockSpec get(Pos p) {
                    return world.getOrDefault(p, BlockSpec.AIR);
                }

                @Override
                public void set(Pos p, BlockSpec b) {
                    world.put(p, b);
                }
            }, new LaneSim.Items() {
                @Override
                public int count(String item) {
                    return stock.getOrDefault(item, 0);
                }

                @Override
                public boolean take(String item) {
                    int n = stock.getOrDefault(item, 0);
                    if (n <= 0) return false;
                    stock.put(item, n - 1);
                    return true;
                }
            }, -64, -64, ART_Y, new AdaptivePacer(1, 1, 8), seed);
            sim.confirmDelay = 5;
            sim.countLag = 5;
            sim.echoes = true;
            sim.baritoneMoves = true;
            sim.onAccept = p -> {
                accepted.merge(p.pos(), 1, Integer::sum);
                if (sim.walking()) {
                    whileWalking.add(p.pos() + " " + p.block().id());
                    walkingFeet.put(p.pos(), new Pos((int) Math.floor(sim.x), ART_Y, (int) Math.floor(sim.z)));
                }
            };
        }

        LaneRun.Params params(boolean greedy) {
            MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // AIRPLACE, silent looks
            b.placeInReachGreedy = greedy;
            sim.silent = PlaceMode.silentRotation(b);
            return LaneRun.Params.of(b, sim.tileArea());
        }

        /** {@code lane} without the cells the world already shows (the engine's rule with the greedy on). */
        Lane unplaced(Lane l) {
            List<Placement> ps = new ArrayList<>();
            for (Placement p : l.placements()) {
                if (!TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR))) ps.add(p);
            }
            return new Lane(l.index(), l.from(), l.to(), List.copyOf(ps));
        }

        /** Runs {@code lanes} in order as one load (each run gets the ones after it), like the engine's BUILD. */
        void runLoad(List<Lane> lanes, boolean greedy) {
            for (int i = 0; i < lanes.size(); i++) {
                Lane open = greedy ? unplaced(lanes.get(i)) : lanes.get(i);
                if (open.placements().isEmpty()) continue;
                List<Lane> load = new ArrayList<>();
                if (greedy) for (Lane l : lanes.subList(i + 1, lanes.size())) load.add(unplaced(l));
                sim.runLane(open, params(greedy), listener, 40_000, load);
            }
        }

        String why() {
            return "accepted=" + sim.accepted + " extras=" + sim.extrasPlaced + " walkingClicks=" + sim.walkingClicks
                + " wrong=" + sim.wrongClicks + " box=" + sim.boxRejects + " far=" + sim.farRejects + " pathBlocked="
                + sim.pathBlocks + " pockets=" + sim.pockets + " skips=" + sim.skipReasons + "\n"
                + String.join("\n", sim.log);
        }

        /** Every cell of {@code lanes} placed exactly once and reported exactly once; the clean-run counters. */
        void assertClean(List<Lane> lanes) {
            int cells = 0;
            for (Lane l : lanes) {
                for (Placement p : l.placements()) {
                    cells++;
                    assertEquals(1, accepted.getOrDefault(p.pos(), 0), "placed once: " + p.pos() + "\n" + why());
                    assertEquals(1, reported.getOrDefault(p.pos(), 0), "reported once: " + p.pos() + "\n" + why());
                }
            }
            assertEquals(cells, sim.accepted, why());
            assertEquals(0, sim.wrongClicks, why());
            assertEquals(0, sim.boxRejects, "never into the bot's box\n" + why());
            assertEquals(0, sim.farRejects, "never beyond the server's airplace range\n" + why());
            assertEquals(0, sim.pathBlocked, "never a full block on Baritone's way\n" + why());
            assertEquals(0, sim.pocketTicks, "never walled in\n" + why());
            assertEquals(1, sim.maxPlacementsPerTick, why());
            assertTrue(sim.skipReasons.isEmpty(), why());
        }
    }

    /** A lane on line x (columns x-w..x+w), rows z0..z1, walked from z0 to z1 (either way), cells by {@code item}. */
    private static Lane lane(int index, int x, int w, int z0, int z1, BiFunction<Integer, Integer, String> item) {
        List<Placement> ps = new ArrayList<>();
        int step = z1 >= z0 ? 1 : -1;
        for (int z = z0; z != z1 + step; z += step) {
            for (int cx = x - w; cx <= x + w; cx++) {
                String id = item.apply(cx, z);
                if (id != null) ps.add(new Placement(new Pos(cx, ART_Y, z), BlockSpec.of(id), id));
            }
        }
        return new Lane(index, new Pos(x, ART_Y, z0 - step), new Pos(x, ART_Y, z1 + step), List.copyOf(ps));
    }

    /** Carpets and full blocks at random (40 % full). */
    private static BiFunction<Integer, Integer, String> mixed(long seed) {
        Map<Long, String> m = new HashMap<>();
        Random r = new Random(seed);
        return (x, z) -> m.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL),
            k -> r.nextDouble() < 0.4 ? TERRACOTTA : r.nextBoolean() ? WHITE : BLACK);
    }

    /** Two 9-wide serpentine lanes: A north to south, then B (the next) south to north. */
    private static List<Lane> serpentine(long seed) {
        BiFunction<Integer, Integer, String> art = mixed(seed);
        return List.of(lane(0, -60, 4, -62, -43, art), lane(1, -51, 4, -43, -62, art));
    }

    private static Bed bed(long seed) {
        Bed b = new Bed(seed);
        b.stock.put(WHITE, 10_000);
        b.stock.put(BLACK, 10_000);
        b.stock.put(TERRACOTTA, 10_000);
        b.sim.teleport(-59.5, ART_Y, -63.5);
        return b;
    }

    /**
     * A lane change: the walk from lane A's end to lane B's start places B's cells on the way (B's own, while Baritone
     * walks), every cell once and reported once, and none of the run's rules broken: no full block on or beside B's
     * walking line, none within a cell of the bot's feet while it walked.
     */
    @Test
    void theWalkToTheNextLanePlacesItsCells() {
        for (long seed = 1; seed <= 3; seed++) {
            Bed b = bed(seed);
            List<Lane> lanes = serpentine(seed);
            b.runLoad(lanes, true);
            b.assertClean(lanes);
            assertTrue(b.sim.walkingClicks >= 5, "cells placed while walking to lane B\n" + b.why());
            assertTrue(String.join("\n", b.sim.log).contains("in-reach greedy: "), b.why());
            for (Placement p : lanes.get(1).placements()) {
                Pos feet = b.walkingFeet.get(p.pos());
                if (feet == null || !p.block().id().equals(TERRACOTTA)) continue;
                assertTrue(Math.abs(p.pos().x() - (-51)) >= 2, "full block on or beside B's line while walking: "
                    + p.pos() + "\n" + b.why());
                assertTrue(Math.max(Math.abs(p.pos().x() - feet.x()), Math.abs(p.pos().z() - feet.z())) >= 2,
                    "full block next to the bot while walking: " + p.pos() + " feet " + feet + "\n" + b.why());
            }
        }
    }

    /** Off, a run given a load does exactly what a run without one does: same ticks, same placements in the same order. */
    @Test
    void offChangesNothing() {
        for (long seed = 1; seed <= 2; seed++) {
            List<List<String>> orders = new ArrayList<>();
            List<Long> ticks = new ArrayList<>();
            for (boolean withLoad : new boolean[]{false, true}) {
                Bed b = bed(seed);
                List<String> order = new ArrayList<>();
                var accept = b.sim.onAccept;
                b.sim.onAccept = p -> {
                    accept.accept(p);
                    order.add(p.pos().toString());
                };
                List<Lane> lanes = serpentine(seed);
                long t = 0;
                for (int i = 0; i < lanes.size(); i++) {
                    List<Lane> load = withLoad ? lanes.subList(i + 1, lanes.size()) : List.of();
                    b.sim.runLane(lanes.get(i), b.params(false), b.listener, 40_000, load);
                    t += b.sim.lastTicks;
                }
                b.assertClean(lanes);
                assertEquals(0, b.sim.walkingClicks, b.why());
                orders.add(order);
                ticks.add(t);
            }
            assertEquals(ticks.get(0), ticks.get(1), "same ticks");
            assertEquals(orders.get(0), orders.get(1), "same placements in the same order");
        }
    }

    /** White, black and terracotta in diagonal stripes: all three in the hotbar while lane A runs. */
    private static String three(int x, int z) {
        int k = Math.floorMod(x + z, 3);
        return k == 0 ? TERRACOTTA : k == 1 ? WHITE : BLACK;
    }

    /** Two narrow lanes side by side: from lane A's line lane B's near columns are in reach. */
    private static List<Lane> sideBySide(BiFunction<Integer, Integer, String> a, BiFunction<Integer, Integer, String> b) {
        return List.of(lane(0, -60, 2, -62, -53, a), lane(1, -55, 2, -53, -62, b));
    }

    /** In lane A's walk, cells of lane B in reach go up when A has no click of its own: the extras. */
    @Test
    void theWalkPlacesALaterLanesCellsInReach() {
        Bed b = bed(4);
        List<Lane> lanes = sideBySide(InReachGreedyTest::three, (x, z) -> BLACK);
        b.runLoad(lanes, true);
        b.assertClean(lanes);
        assertTrue(b.sim.extrasPlaced > 0, "extras placed\n" + b.why());
    }

    /** Full blocks of lane B only in its outer columns (2 off its line), never on or beside its walking line. */
    @Test
    void fullBlocksNeverGoOnOrBesideALaterLanesLine() {
        Bed b = bed(5);
        List<Lane> lanes = sideBySide(InReachGreedyTest::three, (x, z) -> TERRACOTTA);
        b.runLoad(lanes, true);
        b.assertClean(lanes);
        assertTrue(b.sim.extrasPlaced > 0, "outer full blocks of B placed as extras\n" + b.why());
        for (Placement p : b.sim.extraCells) {
            assertEquals(2, Math.abs(p.pos().x() - (-55)), "extra " + p.pos() + " on or beside B's line\n" + b.why());
        }
    }

    /**
     * The load is short of an item (80 white for 50 + 50 cells): no cell of lane B takes it, so lane A gets all of its
     * own.
     */
    @Test
    void anItemTheLoadCannotSpareStaysWithItsLane() {
        Bed b = bed(6);
        b.stock.put(WHITE, 80);
        List<Lane> lanes = sideBySide((x, z) -> WHITE, (x, z) -> WHITE);
        Lane a = lanes.get(0);
        b.sim.runLane(a, b.params(true), b.listener, 40_000, List.of(lanes.get(1)));
        assertEquals(0, b.sim.extrasPlaced, "no white for lane B while the load is short of it\n" + b.why());
        for (Placement p : a.placements()) assertEquals(1, b.accepted.getOrDefault(p.pos(), 0), b.why());
        // with enough white the same walk places some of B's
        Bed c = bed(6);
        c.sim.runLane(a, c.params(true), c.listener, 40_000, List.of(lanes.get(1)));
        assertTrue(c.sim.extrasPlaced > 0, c.why());
    }

    /** A later lane with a LOOK cell (pointed dripstone) keeps its full blocks for its own walk (they would hide it). */
    @Test
    void aLookLaneKeepsItsShadingBlocks() {
        Bed b = bed(7);
        b.stock.put(DRIPSTONE, 100);
        List<Lane> lanes = sideBySide(InReachGreedyTest::three,
            (x, z) -> x == -53 && z == -58 ? DRIPSTONE : (x + z) % 2 == 0 ? TERRACOTTA : BLACK);
        b.sim.runLane(lanes.get(0), b.params(true), b.listener, 40_000, List.of(lanes.get(1)));
        for (Placement p : b.sim.extraCells) {
            assertEquals(BLACK, p.block().id(), "only the low cells of a LOOK lane go early: " + p.pos() + "\n" + b.why());
        }
        assertTrue(b.sim.extrasPlaced > 0, b.why());
    }

    /** An item only in the main inventory is never moved into the hotbar for another lane's cell. */
    @Test
    void noInventoryMoveForALaterLane() {
        Bed b = bed(8);
        b.sim.stackSize = 64; // hotbar stacks: lane A's white only; black waits in the main inventory
        List<Lane> lanes = sideBySide((x, z) -> WHITE, (x, z) -> BLACK);
        b.sim.runLane(lanes.get(0), b.params(true), b.listener, 40_000, List.of(lanes.get(1)));
        assertEquals(0, b.sim.extrasPlaced, b.why());
        assertEquals(0, b.sim.stackMoves, "no move for black\n" + b.why());
    }

    /** {@link LaneRun#clearOfWalk}: a cell from the feet and from Baritone's path; unknown path: never. */
    @Test
    void fullBlocksKeepTheWayOpen() {
        Pos feet = new Pos(0, ART_Y, 0);
        List<Pos> path = List.of(new Pos(0, ART_Y, 0), new Pos(1, ART_Y, 0), new Pos(2, ART_Y, 0));
        assertFalse(LaneRun.clearOfWalk(new Pos(1, ART_Y, 1), feet, null, false));
        assertTrue(LaneRun.clearOfWalk(new Pos(2, ART_Y, 2), feet, null, false));
        assertFalse(LaneRun.clearOfWalk(new Pos(3, ART_Y, 1), feet, path, true), "next to the path");
        assertTrue(LaneRun.clearOfWalk(new Pos(4, ART_Y, 2), feet, path, true));
        assertFalse(LaneRun.clearOfWalk(new Pos(4, ART_Y, 2), feet, null, true), "path unknown");
        assertTrue(LaneRun.early(PlaceMethod.AIRPLACE, 2, true, true, false));
        assertFalse(LaneRun.early(PlaceMethod.AIRPLACE, 1, true, true, false), "full block beside the line");
        assertTrue(LaneRun.early(PlaceMethod.AIRPLACE, 0, false, false, false), "a carpet on the line");
        assertFalse(LaneRun.early(PlaceMethod.LOOK, 3, false, false, false), "LOOK cells keep their rules");
        assertFalse(LaneRun.early(PlaceMethod.AIRPLACE, 3, true, true, true), "shading block in a LOOK lane");
    }
}
