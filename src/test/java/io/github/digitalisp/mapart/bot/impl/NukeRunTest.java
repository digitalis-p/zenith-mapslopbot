package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.bot.impl.DigServer.Item;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NukeRun} (build.breakMode FAST) against a simulated 1.21.4 server ({@link DigServer}, {@link NukeSim}): an
 * ellen-like 128×128 art layer (60% gray terracotta, a few clay, grass, prismarine, raw iron and wart blocks), with
 * Haste II everywhere, without Haste, and with the base's four beacons (the centre 58×60 and the corners uncovered);
 * one or four STARTs per tick; an anti-cheat cancelling every second block of a tick; protected blocks; the tool
 * floor. Prints a numbers table. Ticks are 50 ms (2b2t: 1x).
 */
class NukeRunTest {
    static final int Y = 64;
    static final int FLOOR = 20;
    static final double REACH = 4.5;

    static final BlockType GRAY_TERRACOTTA = new BlockType("minecraft:gray_terracotta", 1.25f, "pickaxe", true);
    static final BlockType PRISMARINE = new BlockType("minecraft:prismarine_bricks", 1.5f, "pickaxe", true);
    static final BlockType RAW_IRON = new BlockType("minecraft:raw_iron_block", 5f, "pickaxe", true);
    static final BlockType WART = new BlockType("minecraft:warped_wart_block", 1f, "hoe", false);
    static final BlockType GRASS = new BlockType("minecraft:grass_block", 0.6f, "shovel", false);
    static final BlockType REDSTONE = new BlockType("minecraft:redstone_block", 5f, "pickaxe", true);

    /** The live kit: netherite pickaxe Efficiency V, diamond axe Efficiency V, netherite shovel Efficiency IV. */
    static Item[] kit() {
        Item[] h = new Item[9];
        h[0] = Item.food();
        h[8] = Item.tool("minecraft:netherite_pickaxe", 5, true, 2031);
        h[7] = Item.tool("minecraft:diamond_axe", 5, true, 1561);
        h[6] = Item.tool("minecraft:netherite_shovel", 4, false, 2031);
        return h;
    }

    /**
     * {@link #kit} with a pickaxe that lasts a whole map: on 2b2t Unbreaking III wears it about once per 4 blocks and
     * the engine mends it between break tasks; here every break costs a use.
     */
    static Item[] durableKit() {
        Item[] h = kit();
        h[8] = new Item("minecraft:netherite_pickaxe", "pickaxe", 9f, 5, 100_000, true, 0);
        return h;
    }

    /** An ellen-like art layer: the full blocks of a mostly gray picture (about 9,900 of 16,384 cells). */
    static Map<Pos, BlockType> ellenLike(long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                double v = r.nextDouble();
                BlockType b;
                if (v < 0.578) b = GRAY_TERRACOTTA;
                else if (v < 0.587) b = PRISMARINE;
                else if (v < 0.596) b = DigServer.CLAY;
                else if (v < 0.599) b = GRASS;
                else if (v < 0.600) b = RAW_IRON;
                else if (v < 0.6006) b = WART;
                else continue;
                m.put(new Pos(x, Y, z), b);
            }
        }
        return m;
    }

    /**
     * The base's four Haste II beacons (level 4, range 50) around a tile at 0..127: west, east, north and south of the
     * centre, so a 58×60 centre and the four corners are uncovered (base-relative: beacons at x −81, 78 and z −84, 77
     * around a tile at −64..63).
     */
    static Predicate<Pos> fourBeacons() {
        int[][] beacons = {{-81 + 64, -2 + 64}, {78 + 64, -1 + 64}, {-1 + 64, -84 + 64}, {-1 + 64, 77 + 64}};
        return p -> {
            for (int[] b : beacons) {
                if (p.x() >= b[0] - 50 && p.x() <= b[0] + 50 && p.z() >= b[1] - 50 && p.z() <= b[1] + 50) return true;
            }
            return false;
        };
    }

    /** One simulated run. */
    static final class Scenario {
        final String name;
        long seed = 11;
        int perTick = 4;
        boolean sweep = true;
        boolean swingEach;
        int floor = FLOOR;
        Item[] hotbar = durableKit();
        Map<Pos, BlockType> blocks = ellenLike(3);
        Set<String> protectedIds = Set.of();
        boolean hasteForever = true;
        Predicate<Pos> zone;
        boolean useZoneInRun = true;
        Consumer<DigServer> server = s -> { };
        long maxTicks = 200_000;
        long watchdogTicks = 300;
        /** Blocks in the world that are no targets (e.g. a block over a target: nothing can aim at it). */
        Map<Pos, BlockType> others = Map.of();
        /** The engine's lane-end question (null: none), given the simulated client. */
        java.util.function.Function<NukeSim, java.util.function.Supplier<String>> pause;
        /** Lane axis (null: AUTO) and anything else set on the simulated client. */
        NukeSweep.Axis axis;
        Consumer<NukeSim> client = c -> { };
        /** build.nukePairSlow (off here: the Grim-clean baseline; its own tests turn it on). */
        boolean pairSlow;
        /** build.nukePocketGuard (on, as by default). */
        boolean pocketGuard = true;
        /** The art layer is cleared afterwards (the NUKE; VERIFY's breaks: false). */
        boolean artCleared = true;
        /** build.nukeSweepBreaksWay (off, as by default). */
        boolean wayBreak;
        /** Where the bot starts (feet); its cell is cleared unless it starts on top of the block there. */
        double startX = 0.5;
        double startZ = 0.5;
        boolean startOnTop;
        /** build.nukeMineMoving (off, as by default). */
        boolean mineMoving;
        /** The sweep's end point (site.platforms[].nukeEndCorner; NaN: none, as by default). */
        double endX = Double.NaN;
        double endZ = Double.NaN;
        /** build.nukeTour (off, as by default) and build.nukeTourMaxDensity. */
        boolean tour;
        double tourMaxDensity = 0.5;
        /** build.nukeLeftoverRoute, build.nukeInstantAckTicks and build.nukeHoldAtSpeed (as by default). */
        boolean leftoverRoute = new MapArtConfig.BuildCfg().nukeLeftoverRoute;
        int instantAckTicks = new MapArtConfig.BuildCfg().nukeInstantAckTicks;
        boolean holdAtSpeed = new MapArtConfig.BuildCfg().nukeHoldAtSpeed;
        /** Targets already air when the run starts (their air came late), none by default. */
        List<Pos> airTargets = List.of();
        /** The run starts where its sweep would have ended ({@link NukeRun#leftoversNow}): no sweep. */
        boolean leftoversNow;
        /** build.nukeLambda and its sub-keys (off, as by default). */
        NukeRun.Lambda lambda = NukeRun.Lambda.OFF;
        /** build.nukeMaxDigsPerSecond (100, as by default). */
        int maxDigs = 100;
        /** {@link NukeRun#serverClock} (1: 2b2t; 0.1 for the local 10x speed mode with {@code miningClock} 0.1). */
        double serverClock = 1.0;

        Scenario(String name) {
            this.name = name;
        }

        Result run() {
            DigServer s = new DigServer(seed, Y);
            s.world.putAll(blocks);
            s.world.putAll(others);
            for (int i = 0; i < 9; i++) s.hotbar[i] = hotbar[i] == null ? null : hotbar[i].copy();
            s.selected = 0;
            server.accept(s);
            // the bot starts on the floor at the north-west corner of the tile (a cleared cell)
            Pos start = new Pos((int) Math.floor(startX), Y, (int) Math.floor(startZ));
            if (!startOnTop) s.world.remove(start);
            NukeSim sim = new NukeSim(s, startX, startZ, floor, protectedIds, REACH, seed + 1);
            sim.elevated = startOnTop;
            sim.floorAt = p -> p.x() >= 0 && p.x() < 128 && p.z() >= 0 && p.z() < 128;
            sim.hasteForever = hasteForever;
            sim.hasteZone = zone;
            client.accept(sim);
            List<Pos> t = new ArrayList<>(s.world.keySet());
            t.removeAll(others.keySet());
            t.addAll(airTargets);
            NukeRun<NukeSim.Shot> run = new NukeRun<>(t, params(), sim, sim::broken,
                pause == null ? null : pause.apply(sim));
            if (sim.pathing) sim.pathCheck = run::pathCheck;
            if (leftoversNow) run.leftoversNow();
            if (serverClock != 1.0) run.serverClock(serverClock);
            TaskStatus st = sim.run(run, maxTicks);
            return new Result(name, st, run, sim, s, t);
        }

        NukeRun.Params params() {
            return new NukeRun.Params(perTick, maxDigs, REACH, floor, 5, sweep, 4, 48, axis, swingEach,
                useZoneInRun ? zone : null, watchdogTicks, pairSlow, pocketGuard, artCleared, wayBreak, mineMoving,
                endX, endZ, tour, tourMaxDensity, leftoverRoute, instantAckTicks, holdAtSpeed, lambda);
        }
    }

    record Result(String name, TaskStatus status, NukeRun<NukeSim.Shot> run, NukeSim sim, DigServer server,
                  List<Pos> targets) {
        double blocksPerSecond() {
            return run.done() * 20.0 / sim.ticks;
        }

        /** Minutes for a 9,879-block map at this rate. */
        double minutesPerMap() {
            return 9879 / blocksPerSecond() / 60.0;
        }

        String line() {
            return String.format(Locale.ROOT, "%-34s %s %5d/%5d in %6d ticks: %5.1f blocks/s, %4.1f min/map, walk %5d"
                    + " ticks %5.0f blocks, instant %d, mined %d, cap %d", name, status, run.done(), targets.size(),
                sim.ticks, blocksPerSecond(), minutesPerMap(), sim.walkTicks, sim.walked, run.dig().instants,
                run.dig().stopped, run.perTick());
        }

        /** The rules every run keeps. */
        void assertRules(int perTick) {
            String why = name + ": " + sim.rules();
            assertTrue(sim.maxStartsInTick <= perTick, why);
            assertEquals(0, sim.startsWithin6OfStop, why);
            assertEquals(0, sim.noSwingTicks, why);
            assertEquals(0, server.tooFar, why);
            assertEquals(0, server.farBreak, why);
            assertEquals(0, server.toolsBroken, why);
            assertEquals(0, server.grim.fastBreakBreakFlags + server.grim.fastBreakDelayFlags, why);
            assertEquals(0, server.grim.wrongBreakFlags, why);
            assertEquals(0, server.grim.positionBreakBFlags, why);
            for (var e : sim.brokenCalls.entrySet()) assertEquals(1, e.getValue(), name + ": broken twice " + e.getKey());
            for (DigServer.Destroy d : server.destroys) {
                if (d.kind() == null) continue;
                assertTrue(d.usesLeftAfter() >= FLOOR, name + ": broke " + d.pos() + " down to " + d.usesLeftAfter());
            }
        }
    }

    private static void print(Result r) {
        System.out.println(r.line());
        System.out.println("    " + r.sim.rules());
        for (String l : r.sim.log) if (l.startsWith("Break round") || l.startsWith("Fast nuke")) System.out.println("    " + l);
    }

    // ---------------------------------------------------------------------------------------------------- tests

    @Test
    void hasteTwoSweepBreaksTheWholeTileWalkingBound() {
        Result r = new Scenario("haste II, 4/tick").run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(4);
        // walking-bound: about 2,000 blocks of lanes at 0.2158 blocks per tick, plus the few mined blocks
        assertTrue(r.sim().ticks < 14_000, "ticks " + r.sim().ticks);
        assertTrue(r.blocksPerSecond() > 14, r.line());
    }

    @Test
    void twoBTwoTLatencyStillKeepsWalking() {
        // 2b2t: about 150-300 ms round trip; the way ahead is cleared early enough that the walk rarely waits
        for (int perTick : new int[]{4, 1}) {
            Scenario sc = new Scenario("haste II, " + perTick + "/tick, 3+3 ticks +jitter 2");
            sc.perTick = perTick;
            sc.server = s -> {
                s.uplink = 3;
                s.downlink = 3;
                s.jitter = 2;
            };
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            r.assertRules(perTick);
            assertTrue(r.minutesPerMap() < (perTick == 4 ? 8.6 : 9.8), r.line());
        }
    }

    @Test
    void denseArtAtOnePerTickIsSweptNotLeftForStanding() {
        // the real ellen NE quadrant is 84 % full blocks: more than one START per tick can keep up with at walking
        // speed, so the walk must wait for the breaking instead of leaving blocks behind (local 1x run, 2026-10-11:
        // 405 of 3,456 left for the standing phase before the backlog hold)
        for (int perTick : new int[]{1, 4}) {
            Scenario sc = new Scenario("dense 84 %, haste II, " + perTick + "/tick");
            sc.perTick = perTick;
            sc.blocks = quarter(dense(9, 0.84));
            sc.server = s -> {
                s.uplink = 3;
                s.downlink = 3;
                s.jitter = 2;
            };
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            r.assertRules(perTick);
            String line = r.run().summaryLine("x");
            int swept = Integer.parseInt(line.replaceAll(".*, (\\d+) swept.*", "$1"));
            assertTrue(swept >= r.targets().size() - 10, line);
        }
    }

    /** A tile of gray terracotta at {@code share} of the cells. */
    static Map<Pos, BlockType> dense(long seed, double share) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) if (r.nextDouble() < share) m.put(new Pos(x, Y, z), GRAY_TERRACOTTA);
        }
        return m;
    }

    @Test
    void oneStartPerTickIsBreakBound() {
        Scenario sc = new Scenario("haste II, 1/tick");
        sc.perTick = 1;
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(1);
        assertEquals(0, r.sim().multiBreakTicks, r.sim().rules());
        assertTrue(r.blocksPerSecond() > 10, r.line());
    }

    @Test
    void withoutHasteEveryTerracottaIsMinedWithTheVanillaTiming() {
        Scenario sc = new Scenario("no haste, 4/tick");
        sc.hasteForever = false;
        sc.blocks = quarter(ellenLike(3));
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(4);
        // START, 2 ticks, STOP, 5 ticks without a START: about 8 ticks per terracotta, 2.5 blocks/s
        assertTrue(r.blocksPerSecond() < 3.0, r.line());
        assertTrue(r.blocksPerSecond() > 1.8, r.line());
    }

    @Test
    void fourBeaconsWaitAtTheEdgeForAFreshHaste() {
        Scenario sc = new Scenario("4 beacons, edge wait");
        sc.hasteForever = false;
        sc.zone = fourBeacons();
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(4);
        Scenario plain = new Scenario("4 beacons, no edge wait");
        plain.hasteForever = false;
        plain.zone = fourBeacons();
        plain.useZoneInRun = false;
        Result p = plain.run();
        print(p);
        assertEquals(TaskStatus.SUCCESS, p.status(), p.run().failure());
        assertTrue(r.run().summaryLine("x").contains("Haste waits"), r.run().summaryLine("x"));
        // waiting for a fresh renewal at the edge saves more than it costs
        assertTrue(r.sim().ticks <= p.sim().ticks, r.line() + " vs " + p.line());
    }

    @Test
    void anAntiCheatCancellingSecondBlocksDropsToOnePerTick() {
        Scenario sc = new Scenario("MultiBreak cancels, 4/tick");
        sc.blocks = quarter(ellenLike(3));
        sc.server = s -> s.cancelMultiBreak = true;
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertEquals(1, r.run().perTick());
        // cancels stop once the run is down to one START per tick (Grim kicks above 100 cancelled packets per second)
        assertTrue(r.server().grimCancelled < 100, "cancelled " + r.server().grimCancelled);
    }

    @Test
    void theOddRefusalKeepsFourPerTick() {
        // local 1x, full tile with beacons: 32 of 9,879 STARTs refused with Grim off; a count of 6 over the whole
        // round dropped the run to one per tick after 4.7 minutes
        Scenario sc = new Scenario("0.5 % refused, 4/tick");
        sc.server = s -> s.refuseInstant = 0.005;
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertTrue(r.server().instantsRefused > 20, "refused " + r.server().instantsRefused);
        assertEquals(4, r.run().perTick(), r.run().summaryLine("x"));
        r.assertRules(4);
        // a refused block is mined to a STOP on its next try (the server breaks it whatever it thinks of the speed)
        assertEquals(0, r.server().grim.airLiquidFlags, r.sim().rules());
    }

    @Test
    void theWatchdogEndsTheSweepThenLeavesTheBlockItIsStuckOn() {
        // a target on the first lane's line with a block over it: nothing can aim at it, so the walk holds for it (it is
        // in the way) and nothing breaks; the watchdog ends the sweep, then leaves that block for later
        Scenario sc = new Scenario("stuck on a covered block");
        sc.blocks = new LinkedHashMap<>();
        for (var e : quarter(ellenLike(3)).entrySet()) {
            if (e.getKey().x() < 24 && e.getKey().z() < 24) sc.blocks.put(e.getKey(), e.getValue());
        }
        Pos stuck = new Pos(4, Y, 8);
        sc.blocks.put(stuck, GRAY_TERRACOTTA);
        sc.others = Map.of(stuck.above(), DigServer.STONE);
        sc.watchdogTicks = 100;
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains("watchdog"), r.run().failure());
        assertTrue(r.run().watchdogTrips() >= 2, "trips " + r.run().watchdogTrips());
        assertTrue(r.sim().log.stream().anyMatch(l -> l.startsWith("Fast nuke watchdog")), String.join("\n", r.sim().log));
        assertEquals(r.targets().size() - 1, r.run().done(), "every other block broken");
        assertTrue(r.server().world.containsKey(stuck));
    }

    /** A redstone-heavy patch like ellen [2,1]: 40 % terracotta, 30 % redstone blocks, a few raw iron blocks. */
    static Map<Pos, BlockType> redstoneHeavy(long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 48; z++) {
                double v = r.nextDouble();
                if (v < 0.40) m.put(new Pos(x, Y, z), GRAY_TERRACOTTA);
                else if (v < 0.70) m.put(new Pos(x, Y, z), REDSTONE);
                else if (v < 0.73) m.put(new Pos(x, Y, z), RAW_IRON);
            }
        }
        return m;
    }

    /** The live conditions of 2026-10-11: 1 START per tick, 2b2t latency, the bot sliding on, the look checked. */
    private static Scenario live(String name, boolean pairSlow) {
        Scenario sc = new Scenario(name);
        sc.perTick = 1;
        sc.pairSlow = pairSlow;
        sc.blocks = redstoneHeavy(4);
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.jitter = 1;
        };
        sc.client = c -> {
            c.slide = true;
            c.geometricLook = true;
        };
        return sc;
    }

    /** Mined slow blocks: 4 ticks of mining each and none aborted (live: 9 per block, 38 aborted at their budget). */
    @Test
    void slowBlocksAreMinedWithoutStalls() {
        Result r = live("redstone patch, mined", false).run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(1);
        assertEquals(0, r.run().dig().aborted, r.run().summaryLine("x"));
        String line = r.run().summaryLine("x");
        int mined = Integer.parseInt(line.replaceAll(".*\\), (\\d+) mined \\((\\d+) ticks\\).*", "$1"));
        int minedTicks = Integer.parseInt(line.replaceAll(".*\\), (\\d+) mined \\((\\d+) ticks\\).*", "$2"));
        assertTrue(minedTicks <= mined * 5.5, "mining ticks per block " + (double) minedTicks / mined + ": " + line);
    }

    /**
     * build.nukePairSlow: slow blocks through the server's delayed slot (START and STOP at once), the walk going on:
     * about 6 ticks a block instead of 10, every block broken, no START within 6 ticks of any STOP, no tool switch while
     * one is pending. Grim's FastBreak (upstream) does flag the early STOPs; the counts are printed.
     */
    @Test
    void slowBlocksThroughTheDelayedSlotAreFaster() {
        Result mined = live("redstone patch, mined", false).run();
        Result paired = live("redstone patch, delayed slot", true).run();
        print(mined);
        print(paired);
        assertEquals(TaskStatus.SUCCESS, paired.status(), paired.run().failure());
        assertEquals(paired.targets().size(), paired.run().done());
        assertEquals(0, paired.sim().startsWithin6OfStop, paired.sim().rules());
        assertEquals(0, paired.server().selectsWhileDelayed, "tool switch while a delayed block was pending");
        assertEquals(0, paired.server().toolsBroken);
        assertTrue(paired.run().dig().delayedBroken > 1000, paired.run().summaryLine("x"));
        assertEquals(0, paired.run().dig().delayedTimedOut, paired.run().summaryLine("x"));
        assertTrue(paired.sim().ticks < mined.sim().ticks * 0.8, paired.line() + " vs " + mined.line());
    }

    /**
     * Pointed dripstone on the walking lines (ellen [2,1] has 253): the walk goes round each on the next column instead
     * of Baritone detours (live: seconds each, skipped lane ends, one stuck for 15 s), and nothing is left over.
     */
    @Test
    void dripstoneOnTheLinesIsSidestepped() {
        BlockType dripstone = new BlockType("minecraft:pointed_dripstone", 1.5f, "pickaxe", true);
        Scenario sc = live("redstone patch, dripstone on the lines", true);
        Map<Pos, BlockType> blocks = new LinkedHashMap<>(redstoneHeavy(4));
        Map<Pos, BlockType> others = new LinkedHashMap<>();
        Random r = new Random(9);
        for (int x : new int[]{4, 13, 22, 31, 40, 49, 58}) {
            for (int i = 0; i < 4; i++) {
                Pos p = new Pos(x, Y, 6 + r.nextInt(36));
                blocks.remove(p);
                others.put(p, dripstone);
            }
        }
        sc.blocks = blocks;
        sc.others = others;
        Result res = sc.run();
        print(res);
        String line = res.run().summaryLine("x");
        assertEquals(TaskStatus.SUCCESS, res.status(), res.run().failure());
        assertEquals(res.targets().size(), res.run().done());
        assertEquals(0, res.run().watchdogTrips(), line);
        assertTrue(line.contains("sidesteps"), line);
        int detours = line.contains(" detours") ? Integer.parseInt(line.replaceAll(".*; (\\d+) detours.*", "$1")) : 0;
        assertTrue(detours <= 3, line);
        int swept = Integer.parseInt(line.replaceAll(".*, (\\d+) swept.*", "$1"));
        assertTrue(swept >= res.targets().size() - 20, line);
    }

    /** An anti-cheat cancelling every early STOP: three delayed blocks never show, then slow blocks are mined again. */
    @Test
    void cancelledEarlyStopsTurnTheDelayedSlotOff() {
        Scenario sc = live("redstone patch, early STOPs dropped", true);
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.dropDelayedStops = 1.0;
        };
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertTrue(r.run().summaryLine("x").contains("pairing off"), r.run().summaryLine("x"));
        assertTrue(r.run().dig().delayedTimedOut <= 12, r.run().summaryLine("x"));
    }

    @Test
    void protectedBlocksAreNeverTouched() {
        Scenario sc = new Scenario("protected prismarine");
        sc.blocks = quarter(ellenLike(3));
        sc.protectedIds = Set.of(PRISMARINE.id());
        Result r = sc.run();
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains("refused (protected)"), r.run().failure());
        for (Pos p : r.targets()) {
            if (r.server().world.get(p) == PRISMARINE || sc.blocks.get(p) == PRISMARINE) {
                assertEquals(0, r.server().digsAt.getOrDefault(p, 0), "dug protected " + p);
            }
        }
        long left = r.server().world.values().stream().filter(b -> b != PRISMARINE).count();
        assertEquals(0, left, "everything else is broken");
    }

    @Test
    void stopsAtTheToolFloorWithoutBreakingThePickaxe() {
        Scenario sc = new Scenario("pickaxe near the floor");
        sc.blocks = quarter(ellenLike(3));
        Item[] h = kit();
        h[8] = Item.tool("minecraft:netherite_pickaxe", 5, true, FLOOR + 40);
        sc.hotbar = h;
        Result r = sc.run();
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains(BotActions.TOOL_AT_FLOOR), r.run().failure());
        Item pick = r.server().hotbar[8];
        assertTrue(pick != null && pick.remaining() >= FLOOR, "pickaxe " + pick);
        assertEquals(0, r.server().toolsBroken);
    }

    /**
     * Mending in the nuke: a netherite pickaxe that wears on every break (no Unbreaking: the whole
     * tile is about 4.8 pickaxes' worth) is due for mending at 50 %. Every stop is a pause at a lane end, never the
     * floor: the bot stands on a lane-end waypoint with nothing in flight and the pickaxe well above the floor; the
     * "mend" (95 %) and a new run from where the bot stands go on until the tile is done.
     */
    @Test
    void pausesAtLaneEndsForMendsAndNeverReachesTheFloor() {
        int due = 2031 / 2;
        Scenario sc = new Scenario("pauses for mends");
        Item[] h = kit();
        h[8] = Item.tool("minecraft:netherite_pickaxe", 5, true, 2031);
        sc.hotbar = h;
        sc.pause = sim -> () -> {
            Item pick = sim.clientSlot(8);
            return pick != null && pick.remaining() <= due ? "netherite_pickaxe due for mending" : null;
        };
        Result r = sc.run();
        NukeSim sim = r.sim();
        DigServer server = r.server();
        NukeRun<NukeSim.Shot> run = r.run();
        TaskStatus st = r.status();
        int pauses = 0;
        int lowest = Integer.MAX_VALUE;
        while (st != TaskStatus.SUCCESS) {
            assertEquals(TaskStatus.FAILED, st, run.failure());
            assertTrue(run.failure().contains(BotActions.BREAK_PAUSED), "pause " + pauses + ": " + run.failure());
            NukeSweep.Plan plan = run.plan();
            assertTrue(plan != null, "paused in a sweep");
            double fx = sim.x;
            double fz = sim.z;
            assertTrue(plan.laneEnds().stream().anyMatch(i -> Math.hypot(plan.waypoints().get(i)[0] - fx,
                plan.waypoints().get(i)[1] - fz) < 0.5), "pause " + pauses + " at a lane end: " + fx + ", " + fz);
            assertTrue(run.canPark(), "nothing in flight at the pause");
            assertEquals(0, run.watchdogTrips(), "a pause is no stall for the watchdog");
            Item pick = server.hotbar[8];
            assertTrue(pick != null && pick.remaining() <= due, "due when it paused: " + pick);
            lowest = Math.min(lowest, pick.remaining());
            assertTrue(pauses++ < 40, "too many pauses");
            server.repair(8, 2031 * 95 / 100);
            List<Pos> rest = new ArrayList<>(server.world.keySet());
            run = new NukeRun<>(rest, sc.params(), sim, sim::broken, sc.pause.apply(sim));
            st = sim.runFrom(run, sim.ticks, 200_000);
        }
        System.out.println("pauses for mends: " + pauses + " pauses, lowest pickaxe " + lowest + " uses, "
            + sim.ticks + " ticks; " + sim.rules());
        assertEquals(0, server.world.size(), "the whole tile broken");
        assertTrue(pauses >= 4, "a pickaxe that wears on every break is mended several times: " + pauses);
        assertTrue(lowest > FLOOR + 200, "paused at a lane end long before the floor: " + lowest);
        assertEquals(0, server.toolsBroken);
        for (DigServer.Destroy d : server.destroys) {
            if (d.kind() != null) assertTrue(d.usesLeftAfter() >= FLOOR, "broke " + d.pos() + " down to " + d.usesLeftAfter());
        }
        for (var e : sim.brokenCalls.entrySet()) assertEquals(1, e.getValue(), "broken twice " + e.getKey());
        assertEquals(0, sim.startsWithin6OfStop, sim.rules());
    }

    /**
     * Local 10x run (2026-10-11): the sweep pressed against a pointed dripstone in the bot's own cell (its random offset
     * puts the shape where the walkability test does not look) for 10 minutes without a log line, sliding sideways enough
     * for the old stuck test. No progress towards the waypoint for 60 walking ticks is a detour now.
     */
    @Test
    void anObstacleTheWalkabilityTestMissesIsWalkedAroundNotPressedAgainstForever() {
        Map<Pos, BlockType> blocks = quarter(ellenLike(3));
        NukeSweep.Plan plan = NukeSweep.plan(blocks.keySet(), NukeSweep.Axis.Z, 4, 2, 0.5, 0.5);
        NukeSweep.Lane lane = plan.lanes().get(1);
        Pos dripstone = new Pos(lane.line(), Y, (lane.from() + lane.to()) / 2);
        blocks.remove(dripstone);
        Scenario sc = new Scenario("hidden obstacle on lane 1");
        sc.blocks = blocks;
        sc.axis = NukeSweep.Axis.Z;
        sc.maxTicks = 40_000;
        sc.client = c -> {
            c.hiddenObstacle = dripstone::equals;
            c.bounce = 0.15;
        };
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure() + " " + r.sim().log);
        assertEquals(r.targets().size(), r.run().done());
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("no progress towards the waypoint")), r.sim().log.toString());
        r.assertRules(4);
    }

    @Test
    void withoutAReasonTheSweepGoesOnPastEveryLaneEnd() {
        Scenario sc = new Scenario("asked, never paused");
        sc.blocks = quarter(ellenLike(3));
        int[] asked = {0};
        sc.pause = sim -> () -> {
            asked[0]++;
            return null;
        };
        Result r = sc.run();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertEquals(r.run().plan().laneEnds().size(), asked[0], "asked once at every lane end");
        r.assertRules(4);
    }

    @Test
    void standingModeBreaksEverythingToo() {
        Scenario sc = new Scenario("standing, 4/tick");
        sc.sweep = false;
        sc.blocks = quarter(ellenLike(3));
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        r.assertRules(4);
    }

    @Test
    void sweepLanesCoverEveryTarget() {
        Map<Pos, BlockType> tile = ellenLike(5);
        for (NukeSweep.Axis axis : NukeSweep.Axis.values()) {
            NukeSweep.Plan plan = NukeSweep.plan(tile.keySet(), axis, 4, 2, 130, 130);
            for (Pos t : tile.keySet()) {
                boolean covered = false;
                for (NukeSweep.Lane l : plan.lanes()) {
                    int u = axis == NukeSweep.Axis.Z ? t.x() : t.z();
                    int v = axis == NukeSweep.Axis.Z ? t.z() : t.x();
                    int lo = Math.min(l.from(), l.to()) - 2;
                    int hi = Math.max(l.from(), l.to()) + 2;
                    if (Math.abs(u - l.line()) <= 4 && v >= lo && v <= hi) covered = true;
                }
                assertTrue(covered, axis + " lane missing for " + t);
            }
            // the start is the lane end nearest to (130, 130): the far corner
            double[] first = plan.waypoints().getFirst();
            assertTrue(first[0] > 100 && first[1] > 100, axis + " start " + first[0] + "," + first[1]);
            assertEquals(15, plan.lanes().size());
        }
    }

    @Test
    void digClientAllowsSeveralInstantStartsPerTickButNoneRightAfterAStop() {
        List<Object[]> sent = new ArrayList<>();
        DigClient.Io<String> io = new DigClient.Io<>() {
            @Override
            public int send(DigClient.Action action, Pos pos, String shot) {
                sent.add(new Object[]{action, pos});
                return sent.size();
            }

            @Override
            public void swing() { }

            @Override
            public double speedNow(Pos pos) {
                return pos.x() == 99 ? 0.6 : 1.3;
            }

            @Override
            public boolean isAir(Pos pos) {
                return false;
            }

            @Override
            public int latestAck() {
                return -1;
            }
        };
        DigClient<String> d = new DigClient<>(io, false);
        assertTrue(d.canStartInstant(10, 3));
        assertTrue(d.startInstant(10, new Pos(1, 0, 0), "s", true));
        assertTrue(d.startInstant(10, new Pos(2, 0, 0), "s", false));
        assertTrue(d.canStartInstant(10, 3));
        assertTrue(d.startInstant(10, new Pos(3, 0, 0), "s", false));
        assertTrue(!d.canStartInstant(10, 3));
        assertTrue(!d.startInstant(11, new Pos(99, 0, 0), "s", false), "not instant: nothing sent");
        assertEquals(3, sent.size());
        // a mined block: STOP, then 5 ticks without any START
        d.start(20, new Pos(99, 0, 0), "s");
        d.tickActive(21, "s", true);
        d.tickActive(22, "s", true);
        assertTrue(!d.digging());
        for (long t = 23; t <= 27; t++) assertTrue(!d.canStartInstant(t, 4), "tick " + t);
        assertTrue(d.canStartInstant(28, 4));
    }

    /** The north-west quarter of a tile (64×64), for the shorter scenarios. */
    static Map<Pos, BlockType> quarter(Map<Pos, BlockType> tile) {
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (var e : tile.entrySet()) if (e.getKey().x() < 64 && e.getKey().z() < 64) m.put(e.getKey(), e.getValue());
        return m;
    }
}
