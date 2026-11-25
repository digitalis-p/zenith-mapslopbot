package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.bot.impl.DigServer.Item;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BreakRun} against a simulated 1.21.4 server ({@link DigServer}, {@link BreakSim}): a mixed nuke of a 128×128
 * tile in SINGLE (pairing off: one block per round, the same vanilla client timing) and DOUBLE, durability floor, the
 * server losing or never finishing delayed breaks, refusals and the local 10x mining clock. Prints a numbers table.
 */
class BreakRunSimTest {
    static final int Y = 64;
    static final int FLOOR = 20;

    // ------------------------------------------------------------------------------------------------- fixtures

    /** (a): netherite pickaxe Efficiency V, diamond axe and shovel Efficiency V, all Mending. */
    static Item[] enchanted() {
        Item[] h = new Item[9];
        h[0] = Item.food();
        h[8] = Item.tool("minecraft:netherite_pickaxe", 5, true, 2031);
        h[7] = Item.tool("minecraft:diamond_axe", 5, true, 1561);
        h[6] = Item.tool("minecraft:diamond_shovel", 5, true, 1561);
        return h;
    }

    /** (b): plain diamond pickaxe, axe and shovel. */
    static Item[] plain() {
        Item[] h = new Item[9];
        h[0] = Item.food();
        h[8] = Item.tool("minecraft:diamond_pickaxe", 0, false, 1561);
        h[7] = Item.tool("minecraft:diamond_axe", 0, false, 1561);
        h[6] = Item.tool("minecraft:diamond_shovel", 0, false, 1561);
        return h;
    }

    /**
     * A mixed art layer: clusters and scattered blocks over the tile, 40% terracotta, 20% concrete, 15% wool, 8%
     * stone/andesite, 5% oak planks, 7% mushroom stem, 5% clay/dirt.
     */
    static Map<Pos, BlockType> mixedTile(long seed) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int c = 0; c < 70; c++) {
            int cx = 2 + r.nextInt(124);
            int cz = 2 + r.nextInt(124);
            int rad = 1 + r.nextInt(4);
            BlockType dominant = pick(r);
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    if (dx * dx + dz * dz > rad * rad || r.nextDouble() > 0.8) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (x < 0 || z < 0 || x > 127 || z > 127) continue;
                    m.put(new Pos(x, Y, z), r.nextDouble() < 0.6 ? dominant : pick(r));
                }
            }
        }
        for (int i = 0; i < 700; i++) m.put(new Pos(r.nextInt(128), Y, r.nextInt(128)), pick(r));
        return m;
    }

    private static BlockType pick(Random r) {
        int v = r.nextInt(100);
        if (v < 40) return DigServer.TERRACOTTA;
        if (v < 60) return DigServer.CONCRETE;
        if (v < 75) return DigServer.WOOL;
        if (v < 79) return DigServer.STONE;
        if (v < 83) return DigServer.ANDESITE;
        if (v < 88) return DigServer.PLANKS;
        if (v < 95) return DigServer.STEM;
        if (v < 98) return DigServer.CLAY;
        return DigServer.DIRT;
    }

    /** One simulated run. */
    static final class Scenario {
        String name = "";
        long seed = 7;
        boolean pairing = true;
        boolean abortBeforeStop;
        int floor = FLOOR;
        Item[] hotbar = plain();
        Map<Pos, BlockType> blocks = Map.of();
        List<Pos> targets = List.of();
        Set<String> protectedIds = Set.of();
        Consumer<DigServer> server = s -> { };
        double lostTicks;
        double serverClock = 1.0;
        /** {@link DigClient#progressInStartTick}. */
        boolean progressInStartTick;
        double approachDepth;
        long maxTicks = 400_000;
        double startX = 0.5;
        double startZ = 0.5;

        Scenario(String name) {
            this.name = name;
        }

        Result run() {
            DigServer s = new DigServer(seed, Y);
            s.world.putAll(blocks);
            for (int i = 0; i < 9; i++) s.hotbar[i] = hotbar[i] == null ? null : hotbar[i].copy();
            s.selected = 0;
            server.accept(s);
            BreakSim sim = new BreakSim(s, startX, startZ, floor, protectedIds, seed + 1);
            sim.lostTicks = lostTicks;
            sim.approachDepth = approachDepth;
            List<Pos> t = targets.isEmpty() ? new ArrayList<>(blocks.keySet()) : targets;
            BreakRun<BreakSim.Shot> run = new BreakRun<>(t, pairing, BreakSim.REACH, floor, abortBeforeStop, sim,
                sim::broken);
            if (serverClock != 1.0) run.serverClock(serverClock);
            run.dig().progressInStartTick(progressInStartTick);
            TaskStatus st = sim.run(run, maxTicks);
            return new Result(name, st, run, sim, s, t);
        }
    }

    record Result(String name, TaskStatus status, BreakRun<BreakSim.Shot> run, BreakSim sim, DigServer server,
                  List<Pos> targets) {
        long ticks() {
            return sim.ticks;
        }

        /** Ticks not spent walking. */
        long miningTicks() {
            return sim.ticks - sim.walkTicks;
        }

        String row() {
            DigClient<BreakSim.Shot> d = run.dig();
            int done = Math.max(1, run.done());
            GrimBreakModel g = server.grim;
            return String.format(Locale.ROOT,
                "%-34s %4d blocks %6d t %5.2f t/b (mining %5.2f) | pairs %4d singles %4d inst %4d | delayed %4d/%4d"
                    + " to %2d | selects %4d | FastBreak max break %5.0f delay %5.0f flags %d/%d | PosBreakB %d",
                name, run.done(), ticks(), (double) ticks() / done, (double) (ticks() - sim.walkTicks) / done,
                run.pairs(), run.singles(), d.instants, d.delayedBroken, d.delayedSent, d.delayedTimedOut,
                sim.selects, g.maxBreakBalance, g.maxDelayBalance, g.fastBreakBreakFlags, g.fastBreakDelayFlags,
                g.positionBreakBFlags);
        }

        void print() {
            System.out.println(row());
            System.out.println("    " + run.summaryLine("done"));
            System.out.println("    " + sim.rules() + "; server: slotBusy " + server.delayedSlotBusy + " stopMismatch "
                + server.stopMismatch + " startWhileDestroying " + server.startWhileDestroying + " abortMismatch "
                + server.abortMismatch + " tooFar " + server.tooFar + " dropped " + server.stopsDropped
                + " neverCompleted " + server.delayedNeverCompleted + " grimCancelled " + server.grimCancelled);
        }

        /** The rules every run keeps (Grim-ish client rules, no select during a delayed destroy, floor, tools). */
        void assertClean(int floor) {
            assertEquals(0, sim.startViolations, name + ": more than one START in a tick");
            assertEquals(0, sim.noSwingTicks, name + ": START/STOP tick without a swing");
            assertEquals(0, sim.multiBreakTicks, name + ": two digs on different blocks/faces in a tick");
            assertEquals(0, server.selectsWhileDelayed, name + ": hotbar select while the server had a delayed destroy");
            assertEquals(0, server.toolsBroken, name + ": a tool broke");
            for (DigServer.Destroy d : server.destroys) {
                assertTrue(d.usesLeftAfter() >= floor, name + ": " + d + " left the tool below the floor");
                String want = d.block().tool();
                assertEquals(want, d.kind(), name + ": " + d.block().id() + " at " + d.pos() + " broken with "
                    + d.item());
            }
            for (var e : server.destroyCount.entrySet()) {
                assertEquals(1, e.getValue(), name + ": broken twice " + e.getKey());
            }
            for (var e : sim.brokenCalls.entrySet()) {
                assertEquals(1, e.getValue(), name + ": reported twice " + e.getKey());
            }
        }

        void assertAllBroken() {
            for (Pos p : targets) assertNull(server.world.get(p), name + ": " + p + " still there");
        }
    }

    static Scenario nuke(String name, Item[] tools, boolean pairing, boolean abort) {
        Scenario s = new Scenario(name);
        s.blocks = mixedTile(42);
        s.hotbar = tools;
        s.pairing = pairing;
        s.abortBeforeStop = abort;
        return s;
    }

    // ------------------------------------------------------------------------------------------------------ tests

    /** SINGLE vs DOUBLE (ABORT before STOP or not) for both tool sets: every block broken, the rules kept. */
    @Test
    void mixedNukeDoubleBeatsSingle() {
        System.out.println("mixed tile: " + mixedTile(42).size() + " blocks");
        Map<String, Result> res = new LinkedHashMap<>();
        for (String set : List.of("a", "b")) {
            Item[] tools = set.equals("a") ? enchanted() : plain();
            String label = set.equals("a") ? "(a) neth/dia Eff V" : "(b) plain diamond";
            res.put(set + "S", nuke(label + " SINGLE", tools, false, false).run());
            res.put(set + "D", nuke(label + " DOUBLE", tools, true, false).run());
            res.put(set + "DA", nuke(label + " DOUBLE+ABORT", tools, true, true).run());
        }
        for (Result r : res.values()) r.print();
        for (String set : List.of("a", "b")) {
            Result single = res.get(set + "S");
            Result dbl = res.get(set + "D");
            Result dblA = res.get(set + "DA");
            System.out.printf(Locale.ROOT, "(%s) DOUBLE/SINGLE ticks %.3f (mining only %.3f), DOUBLE+ABORT/SINGLE %.3f%n",
                set, (double) dbl.ticks() / single.ticks(), (double) dbl.miningTicks() / single.miningTicks(),
                (double) dblA.ticks() / single.ticks());
        }
        for (Result r : res.values()) {
            assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
            r.assertAllBroken();
            r.assertClean(FLOOR);
            assertEquals(r.targets().size(), r.run().done(), r.name());
            assertEquals(0, r.run().dig().delayedTimedOut, r.name());
            GrimBreakModel g = r.server().grim;
            assertEquals(0, g.wrongBreakFlags + g.airLiquidFlags + g.multiBreakFlags + g.noSwingFlags, r.name());
        }
        for (String set : List.of("a", "b")) {
            assertEquals(0, res.get(set + "S").run().pairs());
            assertEquals(0, res.get(set + "S").server().grim.fastBreakFlags(), "vanilla cadence must not flag FastBreak");
            assertEquals(0, res.get(set + "D").server().grim.positionBreakBFlags);
            // the ABORT before the early STOP: Grim 2.0 PositionBreakB flags every one
            assertEquals(res.get(set + "DA").run().pairs(), res.get(set + "DA").server().grim.positionBreakBFlags);
            assertTrue(res.get(set + "D").run().pairs() > 50, set);
        }
        assertTrue(res.get("bD").ticks() < res.get("bS").ticks(), "DOUBLE not faster for plain tools");
        assertTrue(res.get("bDA").ticks() < res.get("bS").ticks(), "DOUBLE+ABORT not faster for plain tools");
        // with the pair stands (BreakRun.PAIR_SPAN): 0.745 and 0.837 of SINGLE's ticks on this tile (0.914 and 0.945
        // when the bot stopped at the first target's reach boundary)
        assertTrue(res.get("bD").ticks() < 0.80 * res.get("bS").ticks(), "plain tools: DOUBLE/SINGLE");
        assertTrue(res.get("aD").ticks() < 0.90 * res.get("aS").ticks(), "enchanted tools: DOUBLE/SINGLE");
        assertTrue(res.get("bD").run().summaryLine("done").contains("pair stands walked to"));
    }

    /**
     * The first progress in the START tick (vanilla on a key press) makes every STOP 50 ms early for Grim 2.0
     * FastBreak, which flags after ~20 blocks and, modifying packets, cancels the STOPs; ZenithProxy's held-key
     * cadence (the default) keeps the balance at 0. DOUBLE trips FastBreak by construction.
     */
    @Test
    void progressInTheStartTickTripsGrimFastBreak() {
        Scenario held = nuke("(b) SINGLE held-key cadence", plain(), false, false);
        Scenario pressed = nuke("(b) SINGLE START-tick progress", plain(), false, false);
        pressed.progressInStartTick = true;
        Scenario pressedCancel = nuke("(b) SINGLE START-tick, Grim cancels", plain(), false, false);
        pressedCancel.progressInStartTick = true;
        pressedCancel.server = s -> s.grimCancel = true;
        Scenario heldCancel = nuke("(b) SINGLE held-key, Grim cancels", plain(), false, false);
        heldCancel.server = s -> s.grimCancel = true;
        Scenario doubleCancel = nuke("(b) DOUBLE held-key, Grim cancels", plain(), true, false);
        doubleCancel.server = s -> s.grimCancel = true;
        Result h = held.run();
        Result p = pressed.run();
        Result pc = pressedCancel.run();
        Result hc = heldCancel.run();
        Result dc = doubleCancel.run();
        for (Result r : List.of(h, p, pc, hc, dc)) r.print();
        assertEquals(0, h.server().grim.fastBreakFlags());
        assertTrue(p.server().grim.fastBreakBreakFlags > 1000, "START-tick progress flags nearly every STOP");
        assertTrue(p.ticks() < h.ticks(), "START-tick progress is one tick faster per block");
        assertEquals(TaskStatus.FAILED, pc.status());
        assertTrue(pc.run().done() < pc.targets().size() / 2, "cancelled STOPs: most blocks given up");
        assertEquals(TaskStatus.SUCCESS, hc.status());
        assertEquals(0, hc.server().grimCancelled);
        // with cancels the delayed STOPs die (timeouts) and pairing switches off; every block still breaks
        assertEquals(TaskStatus.SUCCESS, dc.status(), dc.run().failure());
        dc.assertAllBroken();
        dc.assertClean(FLOOR);
        assertTrue(dc.server().grimCancelled > 0);
        assertTrue(dc.run().pairingOff());
    }

    /**
     * Standing deeper than the reach boundary of every target (a BreakSim experiment, not production) adds few pairs to
     * the pair stands and costs SINGLE more walking than it saves.
     */
    @Test
    void deeperStandFindsMorePartners() {
        int lastPairs = -1;
        for (double depth : new double[]{0, 1.5, 2.5}) {
            Result s = withDepth(nuke("(b) SINGLE stand depth " + depth, plain(), false, false), depth).run();
            Result d = withDepth(nuke("(b) DOUBLE stand depth " + depth, plain(), true, false), depth).run();
            System.out.println(s.row());
            System.out.println(d.row());
            System.out.printf(Locale.ROOT, "    depth %.1f: DOUBLE/SINGLE %.3f%n", depth, (double) d.ticks() / s.ticks());
            assertEquals(TaskStatus.SUCCESS, d.status());
            d.assertClean(FLOOR);
            assertTrue(d.run().pairs() > lastPairs);
            lastPairs = d.run().pairs();
        }
    }

    private static Scenario withDepth(Scenario s, double depth) {
        s.approachDepth = depth;
        return s;
    }

    /** A 5×5 terracotta patch around the bot, one plain diamond pickaxe. */
    static Scenario patch(String name, int pickUses) {
        Scenario s = new Scenario(name);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) m.put(new Pos(x, Y, z), DigServer.TERRACOTTA);
        s.blocks = m;
        Item[] h = new Item[9];
        h[0] = Item.food();
        h[8] = Item.tool("minecraft:diamond_pickaxe", 0, false, pickUses);
        s.hotbar = h;
        s.startX = 5.5;
        s.startZ = 5.5;
        return s;
    }

    @Test
    void durabilityFloorStopsAtTheFloorAndPairsOnlyWithTwoUsesAbove() {
        for (int lat : new int[]{2, 6}) {
            Scenario s = patch("floor 20, pickaxe 25 uses, latency " + lat + "+" + lat, 25);
            int l = lat;
            s.server = sv -> {
                sv.uplink = l;
                sv.downlink = l;
                sv.jitter = l > 2 ? 3 : 0;
            };
            Result r = s.run();
            r.print();
            assertEquals(TaskStatus.FAILED, r.status());
            assertTrue(r.run().failure().contains(BotActions.TOOL_AT_FLOOR), r.run().failure());
            assertEquals(5, r.run().done(), "25 uses, floor 20: five blocks");
            assertEquals(5, r.server().destroys.size());
            assertEquals(20, r.server().hotbar[8].remaining());
            r.assertClean(FLOOR);
            // 25 -> 23 and 23 -> 21 as pairs; at 21 only one use is above the floor: a single
            assertEquals(2, r.run().pairs());
            assertEquals(2, r.run().dig().delayedSent);
            DigServer.Destroy last = r.server().destroys.get(4);
            assertFalse(last.delayed(), "the last block (21 -> 20) is no delayed block");
        }
    }

    @Test
    void spareToolTakesOverBetweenRounds() {
        Scenario s = patch("floor 20, mending pickaxe 25 uses + spare 300", 25);
        s.hotbar[8] = Item.tool("minecraft:diamond_pickaxe", 0, true, 25); // Mending: preferred while above the floor
        s.hotbar[5] = Item.tool("minecraft:diamond_pickaxe", 0, false, 300);
        Result r = s.run();
        r.print();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        r.assertAllBroken();
        r.assertClean(FLOOR);
        assertEquals(20, r.server().hotbar[8].remaining());
        assertEquals(300 - 20, r.server().hotbar[5].remaining());
        assertEquals(5, r.sim().selected());
        assertEquals(0, r.server().selectsWhileDelayed, "the switch never happens while a delayed block is pending");
    }

    @Test
    void droppedDelayedStopsAreMinedAgainAlone() {
        Scenario s = nuke("(b) DOUBLE, server drops 20% delayed STOPs", plain(), true, false);
        s.server = sv -> sv.dropDelayedStops = 0.2;
        Result r = s.run();
        r.print();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        r.assertAllBroken();
        r.assertClean(FLOOR);
        assertTrue(r.server().stopsDropped > 10);
        assertEquals(r.server().stopsDropped, r.run().dig().delayedTimedOut);
        // random losses: pairing goes on for a long time (3 timeouts in a row may still come by chance, 0.8% per pair)
        assertTrue(r.run().pairs() > 100, "pairs " + r.run().pairs());
        assertTrue(r.run().summaryLine("done").contains(r.server().stopsDropped + " timed out"));
    }

    @Test
    void allDelayedStopsDroppedSwitchesPairingOff() {
        Scenario s = nuke("(b) DOUBLE, server drops every delayed STOP", plain(), true, false);
        s.server = sv -> sv.dropDelayedStops = 1.0;
        Result r = s.run();
        r.print();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        r.assertAllBroken();
        r.assertClean(FLOOR);
        assertTrue(r.run().pairingOff());
        assertEquals(3, r.run().dig().delayedTimedOut);
        assertEquals(3, r.run().pairs());
        assertEquals(3, r.server().stopsDropped);
        assertTrue(r.sim().log.contains(
                "Double break: 3 delayed breaks in a row never showed; pairing off for the rest of this round"),
            r.sim().log.toString());
        assertTrue(r.run().summaryLine("done").contains("pairing off after 3 timeouts"));
    }

    @Test
    void delayedDestroyThatNeverCompletesIsMinedAgain() {
        Scenario s = nuke("(b) DOUBLE, 40 delayed destroys never complete", plain(), true, false);
        List<Pos> concrete = new ArrayList<>();
        for (var e : s.blocks.entrySet()) if (e.getValue() == DigServer.CONCRETE) concrete.add(e.getKey());
        List<Pos> never = concrete.subList(0, 40);
        s.server = sv -> sv.neverComplete.addAll(never);
        Result r = s.run();
        r.print();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        r.assertAllBroken();
        r.assertClean(FLOOR);
        assertTrue(r.server().delayedNeverCompleted > 0);
        assertEquals(r.server().delayedNeverCompleted, r.run().dig().delayedTimedOut);
        for (DigServer.Destroy d : r.server().destroys) {
            if (never.contains(d.pos())) assertFalse(d.delayed(), d.pos() + " broke as a delayed block");
        }
    }

    @Test
    void protectedUnbreakableAndAirTargets() {
        Scenario s = patch("protected wool, bedrock, air", 1561);
        Map<Pos, BlockType> m = new LinkedHashMap<>(s.blocks);
        Pos wool1 = new Pos(4, Y, 4);
        Pos wool2 = new Pos(6, Y, 6);
        Pos bedrock = new Pos(5, Y, 7);
        Pos air = new Pos(5, Y, 5);
        m.put(wool1, DigServer.WOOL);
        m.put(wool2, DigServer.WOOL);
        m.put(bedrock, DigServer.BEDROCK);
        m.remove(air);
        s.blocks = m;
        List<Pos> targets = new ArrayList<>(m.keySet());
        targets.add(air);
        s.targets = targets;
        s.protectedIds = Set.of("minecraft:white_wool");
        Result r = s.run();
        r.print();
        assertEquals(TaskStatus.FAILED, r.status());
        String f = r.run().failure();
        assertTrue(f.startsWith("refused (protected): "), f);
        assertTrue(f.contains("minecraft:white_wool at " + wool1) && f.contains("minecraft:white_wool at " + wool2), f);
        assertTrue(f.contains("unbreakable minecraft:bedrock at " + bedrock), f);
        for (Pos p : List.of(wool1, wool2, bedrock, air)) {
            assertNull(r.server().digsAt.get(p), "a dig packet for " + p);
        }
        assertEquals(DigServer.WOOL, r.server().world.get(wool1));
        assertEquals(1, r.sim().brokenCalls.get(air), "air from the start counts as broken");
        assertEquals(targets.size() - 3, r.run().done());
        for (Pos p : targets) {
            if (!p.equals(wool1) && !p.equals(wool2) && !p.equals(bedrock)) assertNull(r.server().world.get(p));
        }
        r.assertClean(FLOOR);
    }

    /**
     * Locally ZenithProxy's client ticks 10x (client.tickRate 10) while Paper's mining clock follows the wall clock:
     * 0.1 server mining ticks per client tick. With {@link BreakRun#serverClock} 0.1 the run is clean; without it the
     * STOPs arrive with a tenth of the progress (into the delayed slot, or refused while it is busy) and most blocks
     * are given up.
     */
    @Test
    void localTenTimesClientNeedsTheServerClock() {
        Map<String, Result> res = new LinkedHashMap<>();
        for (boolean pairing : new boolean[]{false, true}) {
            for (boolean scaled : new boolean[]{true, false}) {
                String name = "(b) 10x " + (pairing ? "DOUBLE" : "SINGLE") + (scaled ? " serverClock 0.1" : " unscaled");
                Scenario s = nuke(name, plain(), pairing, false);
                s.server = sv -> {
                    sv.miningClock = 0.1;
                    sv.uplink = 1;
                    sv.downlink = 1;
                };
                if (scaled) s.serverClock = 0.1;
                Result r = s.run();
                r.print();
                res.put((pairing ? "D" : "S") + (scaled ? "+" : "-"), r);
            }
        }
        for (String k : List.of("S+", "D+")) {
            Result r = res.get(k);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            r.assertAllBroken();
            r.assertClean(FLOOR);
            DigClient<BreakSim.Shot> d = r.run().dig();
            assertEquals(0, d.delayedTimedOut + d.refused + d.unknown + d.aborted, k);
        }
        assertTrue(res.get("D+").run().pairs() > 50);
        assertTrue(res.get("D+").ticks() < res.get("S+").ticks());
        for (String k : List.of("S-", "D-")) {
            Result r = res.get(k);
            assertEquals(TaskStatus.FAILED, r.status(), k + ": without the server clock the run gives blocks up");
            assertTrue(r.run().dig().refused > 1000, k);
            assertEquals(0, r.server().toolsBroken);
        }
        assertTrue(res.get("D-").run().pairingOff());
    }

    /**
     * The engine's park request (eat, stand still) is granted within a few ticks even while pairs run back to back
     * (as BreakBlocksTask: holdForPark, then canPark before each tick); while parked the bot holds food, which must
     * never happen with a delayed block or an unanswered STOP on the server.
     */
    @Test
    void parkRequestIsGrantedBetweenPairs() {
        Scenario sc = nuke("(b) DOUBLE with park requests", plain(), true, false);
        DigServer s = new DigServer(sc.seed, Y);
        s.world.putAll(sc.blocks);
        for (int i = 0; i < 9; i++) s.hotbar[i] = sc.hotbar[i] == null ? null : sc.hotbar[i].copy();
        BreakSim sim = new BreakSim(s, 0.5, 0.5, FLOOR, Set.of(), sc.seed + 1);
        List<Pos> targets = new ArrayList<>(sc.blocks.keySet());
        BreakRun<BreakSim.Shot> run = new BreakRun<>(targets, true, BreakSim.REACH, FLOOR, false, sim, sim::broken);
        TaskStatus st = TaskStatus.RUNNING;
        int requests = 0;
        long maxWait = 0;
        long askedAt = -1;
        long parkedUntil = -1;
        long t = 0;
        for (; t < 400_000 && st == TaskStatus.RUNNING; t++) {
            sim.beginTick(t);
            if (parkedUntil >= 0) {
                sim.look(null); // parked: the engine stands still (and would eat); the run does not tick
                if (t >= parkedUntil) {
                    parkedUntil = -1;
                    run.holdForPark(false);
                    run.resume();
                }
            } else {
                if (askedAt < 0 && t % 1500 == 700) {
                    askedAt = t;
                    requests++;
                }
                if (askedAt >= 0 && run.canPark()) {
                    assertFalse(s.hasDelayedDestroy, "parked with a delayed destroy on the server at " + t);
                    maxWait = Math.max(maxWait, t - askedAt);
                    askedAt = -1;
                    parkedUntil = t + 40;
                    sim.look(null);
                } else {
                    run.holdForPark(askedAt >= 0);
                    st = run.tick(t);
                }
            }
            sim.endTick(t);
            s.tick(t);
        }
        System.out.println("park requests " + requests + ", longest wait " + maxWait + " ticks; "
            + run.summaryLine("done"));
        assertEquals(TaskStatus.SUCCESS, st, run.failure());
        assertTrue(requests >= 10, "requests " + requests);
        assertTrue(maxWait <= 40, "a park waited " + maxWait + " ticks");
        assertEquals(0, s.selectsWhileDelayed);
        for (Pos p : targets) assertNull(s.world.get(p), p + " still there");
        assertTrue(run.pairs() > 100, "pairs " + run.pairs());
    }

    /**
     * A task cancelled (the setback escape, a stop) right after a delayed START: the server keeps that block in its
     * delayed slot, and the next break task waits for it ({@link BreakRun#lingering}) before it pairs or switches the
     * held item, so no select lands while the server holds it and no early STOP meets a busy slot.
     */
    @Test
    void cancelledWithADelayedBlockPendingHandsItOn() {
        Scenario sc = nuke("(b) DOUBLE cancelled mid-pair", plain(), true, false);
        DigServer s = new DigServer(sc.seed, Y);
        s.world.putAll(sc.blocks);
        for (int i = 0; i < 9; i++) s.hotbar[i] = sc.hotbar[i] == null ? null : sc.hotbar[i].copy();
        BreakSim sim = new BreakSim(s, 0.5, 0.5, FLOOR, Set.of(), sc.seed + 1);
        List<Pos> targets = new ArrayList<>(sc.blocks.keySet());
        BreakRun<BreakSim.Shot> first = new BreakRun<>(targets, true, BreakSim.REACH, FLOOR, false, sim, sim::broken);
        long t = 0;
        int cancels = 0;
        BreakRun<BreakSim.Shot> run = first;
        TaskStatus st = TaskStatus.RUNNING;
        for (; t < 400_000 && st == TaskStatus.RUNNING; t++) {
            sim.beginTick(t);
            st = run.tick(t);
            if (st == TaskStatus.RUNNING && cancels < 5 && run.pairs() >= 20 * (cancels + 1)
                && run.dig().delayedPending()) {
                // cancelled now: the next task starts on the next tick with what is left
                run.release();
                var d = run.pendingDelayed(t);
                assertTrue(d != null);
                List<Pos> left = new ArrayList<>();
                for (Pos p : targets) if (!sim.brokenCalls.containsKey(p)) left.add(p);
                BreakRun<BreakSim.Shot> next = new BreakRun<>(left, true, BreakSim.REACH, FLOOR, false, sim,
                    sim::broken);
                next.lingering(d.getKey(), d.getValue());
                run = next;
                cancels++;
            }
            sim.endTick(t);
            s.tick(t);
        }
        System.out.println("cancelled " + cancels + " times with a delayed block pending; " + run.summaryLine("done"));
        assertEquals(TaskStatus.SUCCESS, st, run.failure());
        assertEquals(5, cancels);
        for (Pos p : targets) assertNull(s.world.get(p), p + " still there");
        assertEquals(0, s.selectsWhileDelayed, "a select reached the server while it held a delayed block");
        assertEquals(0, s.delayedSlotBusy, "an early STOP met the busy delayed slot");
        assertEquals(0, sim.startViolations + sim.noSwingTicks + sim.multiBreakTicks);
        for (var e : sim.brokenCalls.entrySet()) assertEquals(1, e.getValue(), "reported twice " + e.getKey());
        for (DigServer.Destroy d : s.destroys) assertTrue(d.usesLeftAfter() >= FLOOR, d + " below the floor");
    }

    /** Jitter, high latency, lost input ticks and a 10 TPS server (Paper's wall clock): slower, still clean. */
    @Test
    void robustAgainstJitterLatencyLostTicksAndLag() {
        List<Scenario> list = new ArrayList<>();
        Scenario s = nuke("(b) DOUBLE jitter 0-3", plain(), true, false);
        s.server = sv -> sv.jitter = 3;
        list.add(s);
        s = nuke("(b) DOUBLE latency 4+4 jitter 0-4", plain(), true, false);
        s.server = sv -> {
            sv.uplink = 4;
            sv.downlink = 4;
            sv.jitter = 4;
        };
        list.add(s);
        s = nuke("(b) DOUBLE 10% lost input ticks", plain(), true, false);
        s.lostTicks = 0.1;
        list.add(s);
        s = nuke("(b) DOUBLE 10 TPS (Paper clock)", plain(), true, false);
        s.server = sv -> sv.tpsFactor = 0.5;
        list.add(s);
        for (Scenario sc : list) {
            Result r = sc.run();
            r.print();
            assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
            r.assertAllBroken();
            r.assertClean(FLOOR);
            assertEquals(0, r.run().dig().delayedTimedOut, r.name());
        }
    }
}
