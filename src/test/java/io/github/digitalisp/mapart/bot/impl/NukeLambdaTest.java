package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.bot.impl.DigServer.Item;
import io.github.digitalisp.mapart.bot.impl.NukeRunTest.Result;
import io.github.digitalisp.mapart.bot.impl.NukeRunTest.Scenario;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.bot.impl.NukeRunTest.FLOOR;
import static io.github.digitalisp.mapart.bot.impl.NukeRunTest.Y;

/**
 * {@link NukeRun} with Lambda's OldGrim scheme ({@code build.nukeLambda}) against the simulated 1.21.4 server ({@link
 * DigServer}, {@link GrimBreakModel}, {@link NukeSim}):
 * the packets of each tick, fake STARTs never on a real block, every block broken once by the vanilla dig state, no
 * tool below the floor, no START in the air, off by default; and the throughput on a lapis tile (lapis_block hardness
 * 3, netherite pickaxe Efficiency V, with and without Haste II) against the current FAST, in ticks per block.
 */
class NukeLambdaTest {
    static final BlockType LAPIS = new BlockType("minecraft:lapis_block", 3f, "pickaxe", true);
    static final NukeRun.Lambda ON = new NukeRun.Lambda(true, 8, true, true, true);

    /** A {@code size}×{@code size} patch of lapis blocks at {@code share} of the cells. */
    static Map<Pos, BlockType> lapis(long seed, double share, int size) {
        Random r = new Random(seed);
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) if (r.nextDouble() < share) m.put(new Pos(x, Y, z), LAPIS);
        }
        return m;
    }

    /** 2b2t: 3+3 ticks of latency, jitter 2; one instant START per tick for the current FAST (the live setting). */
    static Scenario lapisRun(String name, boolean haste, NukeRun.Lambda lambda, boolean pairSlow) {
        Scenario sc = new Scenario(name);
        sc.perTick = 1;
        sc.pairSlow = pairSlow;
        sc.lambda = lambda;
        sc.hasteForever = haste;
        sc.blocks = lapis(5, 0.9, 64);
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.jitter = 2;
        };
        return sc;
    }

    static String lambdaRules(Result r) {
        return String.format(Locale.ROOT, "; fake STARTs %d (in world %d, server out-of-world digs %d), instant <6"
                + " after STOP %d, multi-swing ticks %d, airborne STARTs %d, grim AirLiquid out of world %d",
            r.sim().fakeStarts, r.sim().fakeInWorld, r.server().outOfWorldDigs, r.sim().instantWithin6OfStop,
            r.sim().multiSwingTicks, r.sim().airborneStarts, r.server().grim.airLiquidOutOfWorld);
    }

    /** The rules a run with Lambda's scheme keeps. */
    static void assertLambdaRules(Result r, int perTick) {
        assertLambdaRules(r, perTick, false);
    }

    /**
     * {@code lateAir}: the server sends some air after the ack ({@link DigServer#lateAir}), so a retry may START a block
     * that is already air (AirLiquidBreak), as without Lambda's scheme.
     */
    static void assertLambdaRules(Result r, int perTick, boolean lateAir) {
        String why = r.name() + ": " + r.sim().rules() + lambdaRules(r);
        NukeSim sim = r.sim();
        DigServer s = r.server();
        assertEquals(0, sim.fakeInWorld, why);
        assertEquals(sim.fakeStarts, s.outOfWorldDigs, why);
        assertEquals(0, sim.instantWithin6OfStop, why);
        assertEquals(0, sim.noSwingTicks, why);
        assertEquals(0, sim.multiSwingTicks, why);
        assertEquals(0, sim.airborneStarts, why);
        assertEquals(0, s.tooFar, why);
        assertEquals(0, s.farBreak, why);
        assertEquals(0, s.toolsBroken, why);
        if (!lateAir) assertEquals(0, s.grim.airLiquidFlags, why);
        assertTrue(sim.maxStartsInTick <= Math.max(perTick, 2), why);
        for (var e : sim.brokenCalls.entrySet()) assertEquals(1, e.getValue(), r.name() + ": broken twice " + e.getKey());
        for (var e : s.destroyCount.entrySet()) {
            assertEquals(1, e.getValue(), r.name() + ": destroyed twice " + e.getKey());
            assertFalse(DigClient.outOfWorld(e.getKey()), r.name() + ": destroyed " + e.getKey());
        }
        for (DigServer.Destroy d : s.destroys) {
            if (d.kind() == null) continue;
            assertTrue(d.usesLeftAfter() >= FLOOR, r.name() + ": broke " + d.pos() + " down to " + d.usesLeftAfter());
        }
    }

    private static void print(Result r) {
        System.out.println(r.line());
        System.out.println("    " + r.sim().rules() + lambdaRules(r));
        for (String l : r.sim().log) if (l.startsWith("Break round") || l.startsWith("Fast nuke")) System.out.println("    " + l);
    }

    // ---------------------------------------------------------------------------------------------------- tests

    @Test
    void offByDefault() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertFalse(b.nukeLambda);
        assertEquals(8, b.nukeLambdaStartsPerTick);
        assertTrue(b.nukeLambdaFakeStarts && b.nukeLambdaBurst && b.nukeLambdaDoubleBreak);
        assertFalse(NukeRun.Lambda.OFF.on());
        Scenario sc = lapisRun("lapis, current FAST", false, NukeRun.Lambda.OFF, false);
        sc.blocks = lapis(5, 0.9, 24);
        assertFalse(sc.params().lambda().on());
        Result r = sc.run();
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(0, r.sim().fakeStarts);
        assertEquals(0, r.run().dig().lambdaMined + r.run().dig().lambdaSecondaries + r.run().dig().lambdaQuick);
        assertFalse(r.run().summaryLine("x").contains("Lambda"), r.run().summaryLine("x"));
        assertEquals(0, r.sim().startsWithin6OfStop, r.sim().rules());
    }

    /** The packets of each tick of a short standing run: four lapis blocks in reach, no Haste, 2+2 ticks latency. */
    @Test
    void packetsOfEachTickFollowLambda() {
        Scenario sc = new Scenario("four lapis, standing");
        sc.lambda = ON;
        sc.sweep = false;
        sc.hasteForever = false;
        Map<Pos, BlockType> m = new LinkedHashMap<>();
        for (int[] c : new int[][]{{2, 0}, {2, 1}, {0, 2}, {1, 2}}) m.put(new Pos(c[0], Y, c[1]), LAPIS);
        sc.blocks = m;
        sc.client = c -> c.recordPackets = true;
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(4, r.run().done());
        assertLambdaRules(r, 8);
        TreeMap<Long, List<String>> byTick = new TreeMap<>();
        for (String p : r.sim().packets) {
            int sp = p.indexOf(' ');
            byTick.computeIfAbsent(Long.parseLong(p.substring(0, sp)), k -> new ArrayList<>()).add(p.substring(sp + 1));
        }
        long t0 = -1;
        for (var e : byTick.entrySet()) {
            if (e.getValue().stream().anyMatch(s -> s.startsWith("START"))) {
                t0 = e.getKey();
                break;
            }
        }
        assertTrue(t0 >= 0, String.valueOf(byTick));
        // tick 0: START a, its fake, swing, the early STOP of a (the server's delayed slot), START b and its fake
        List<String> first = new ArrayList<>(byTick.get(t0));
        first.removeIf(s -> s.startsWith("select"));
        assertEquals(6, first.size(), first.toString());
        String a = first.get(0).substring(6);
        String b = first.get(4).substring(6);
        assertEquals(List.of("START " + a, "START " + fake(a), "swing", "STOP " + a, "START " + b, "START " + fake(b)),
            first);
        for (long t = t0 + 1; t < t0 + 6; t++) assertEquals(List.of("swing"), byTick.get(t), "tick " + (t - t0));
        // tick 6: b's burst of 22 fake STARTs and its STOP, then at once c (to the delayed slot) and d
        List<String> six = byTick.get(t0 + 6);
        assertEquals(22 + 2 + 5, six.size(), six.toString());
        for (int i = 0; i < 22; i++) assertEquals("START " + fake(b), six.get(i));
        assertEquals("STOP " + b, six.get(22));
        assertEquals("swing", six.get(23));
        String c = six.get(24).substring(6);
        String d = six.get(27).substring(6);
        assertEquals(List.of("START " + c, "START " + fake(c), "STOP " + c, "START " + d, "START " + fake(d)),
            six.subList(24, 29));
        for (long t = t0 + 7; t < t0 + 12; t++) assertEquals(List.of("swing"), byTick.get(t), "tick " + (t - t0));
        List<String> twelve = byTick.get(t0 + 12);
        assertEquals(24, twelve.size(), twelve.toString());
        assertEquals("STOP " + d, twelve.get(22));
        assertEquals(Set.of(a, b, c, d).size(), 4);
        // the vanilla dig state: a and c through the delayed slot, b and d by their STOP
        int delayed = 0;
        for (DigServer.Destroy x : r.server().destroys) if (x.delayed()) delayed++;
        assertEquals(2, delayed);
        assertEquals(2, r.server().delayedEntered);
        assertEquals(0, r.server().delayedSlotBusy);
        assertEquals(0, r.server().stopMismatch);
    }

    private static String fake(String pos) {
        String[] p = pos.split(",");
        return p[0] + "," + (Integer.parseInt(p[1]) + DigClient.OLD_GRIM_Y_OFFSET) + "," + p[2];
    }

    /** {@link #lapisRun} with the client walking as live on soul sand (slide, top speed 0.49, the look checked). */
    static Scenario liveLike(String name, boolean haste, NukeRun.Lambda lambda, boolean pairSlow, boolean mineMoving) {
        Scenario sc = lapisRun(name, haste, lambda, pairSlow);
        sc.mineMoving = mineMoving;
        sc.client = c -> {
            c.slide = true;
            c.topSpeed = 0.49;
            c.geometricLook = true;
            c.keysFollowLook = true;
        };
        return sc;
    }

    /**
     * The throughput on a lapis tile (64×64 at 90 %, 2b2t latency): the current FAST (slow blocks mined, as live, or
     * through the delayed slot, the default) against Lambda's scheme, with and without Haste II; on the plain walk
     * (0.2158 a tick, no slide) and live-like on soul sand, with and without {@code build.nukeMineMoving}. Prints the
     * table.
     */
    @Test
    void lapisTileThroughputAgainstTheCurrentFast() {
        List<String[]> rows = new ArrayList<>();
        NukeRun.Lambda noBurst = new NukeRun.Lambda(true, 8, true, false, true);
        NukeRun.Lambda noDouble = new NukeRun.Lambda(true, 8, true, true, false);
        for (boolean haste : new boolean[]{false, true}) {
            String h = haste ? "Haste II" : "no Haste";
            Result mined = lapisRun("plain: FAST, mined (" + h + ")", haste, NukeRun.Lambda.OFF, false).run();
            Result slot = lapisRun("plain: FAST, delayed slot (" + h + ")", haste, NukeRun.Lambda.OFF, true).run();
            Result lambda = lapisRun("plain: Lambda (" + h + ")", haste, ON, false).run();
            Scenario wide = lapisRun("plain: Lambda, 150 digs/s (" + h + ")", haste, ON, false);
            wide.maxDigs = 150;
            Result lambdaWide = wide.run();
            Result nb = lapisRun("plain: Lambda, no burst (" + h + ")", haste, noBurst, false).run();
            Result nd = lapisRun("plain: Lambda, no double break (" + h + ")", haste, noDouble, false).run();
            Result lMined = liveLike("soul sand: FAST, mined (" + h + ")", haste, NukeRun.Lambda.OFF, false, false).run();
            Result lMinedMove = liveLike("soul sand: FAST, mined, mining on the move (" + h + ")", haste,
                NukeRun.Lambda.OFF, false, true).run();
            Result lSlot = liveLike("soul sand: FAST, delayed slot (" + h + ")", haste, NukeRun.Lambda.OFF, true, false)
                .run();
            Result lLambda = liveLike("soul sand: Lambda (" + h + ")", haste, ON, false, false).run();
            Result lLambdaMove = liveLike("soul sand: Lambda, mining on the move (" + h + ")", haste, ON, false, true)
                .run();
            List<Result> all = List.of(mined, slot, lambda, lambdaWide, nb, nd, lMined, lMinedMove, lSlot, lLambda,
                lLambdaMove);
            for (Result r : all) {
                print(r);
                assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
                assertEquals(r.targets().size(), r.run().done(), r.name());
                assertTrue(r.server().world.isEmpty(), r.name() + ": left " + r.server().world.size());
                rows.add(new String[]{r.name(), String.valueOf(r.targets().size()), String.valueOf(r.sim().ticks),
                    String.format(Locale.ROOT, "%.2f", (double) r.sim().ticks / r.run().done()),
                    String.format(Locale.ROOT, "%.1f", r.blocksPerSecond()),
                    String.valueOf(r.server().grim.fastBreakDelayFlags + r.server().grim.fastBreakBreakFlags),
                    String.valueOf(r.server().grim.wrongBreakFlags),
                    String.valueOf(r.server().grim.airLiquidOutOfWorld)});
            }
            for (Result r : List.of(lambda, lambdaWide, nb, nd, lLambda, lLambdaMove)) assertLambdaRules(r, 8);
            mined.assertRules(1);
            lMined.assertRules(1);
            for (Result[] pair : new Result[][]{{mined, lambda}, {lMined, lLambda}, {lMinedMove, lLambdaMove}}) {
                double m = (double) pair[0].sim().ticks / pair[0].run().done();
                double l = (double) pair[1].sim().ticks / pair[1].run().done();
                assertTrue(l < m * 0.75, pair[1].name() + " " + l + " vs " + pair[0].name() + " " + m + " ticks per block");
            }
        }
        System.out.println("| Run | blocks | ticks | ticks/block | blocks/s | FastBreak | WrongBreak | AirLiquid (fake) |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        for (String[] row : rows) System.out.println("| " + String.join(" | ", row) + " |");
    }

    /**
     * 2b2t's late air ({@link DigServer#lateAir}: 4 % of the blocks show air 1-10 ticks after their ack): the Lambda
     * STOPs wait as instant blocks do ({@code build.nukeInstantAckTicks}), the scheme stays on, every block breaks once.
     */
    @Test
    void lateAirKeepsLambdaOn() {
        for (int ack : new int[]{2, 4}) {
            Scenario sc = liveLike("lapis, Lambda, late air, ack " + ack, false, ON, false, true);
            sc.instantAckTicks = ack;
            sc.server = sc.server.andThen(s -> {
                s.lateAir = 0.04;
                s.lateAirTicks = 10;
            });
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertTrue(r.server().world.isEmpty());
            assertTrue(r.server().lateAirs > 0, "late air happened");
            assertTrue(r.run().lambdaOn(), r.run().summaryLine("x"));
            assertLambdaRules(r, 8, true);
        }
    }

    /** NukeLeftoversTest's j0024-like rounds (soul sand, late air, 0.3 % really refused) with Lambda's scheme. */
    @Test
    void j0024LikeRoundsWithLambda() {
        for (long seed = 1; seed <= 2; seed++) {
            Scenario sc = NukeLeftoversTest.j0024(seed, new NukeLeftoversTest.Keys(true, 4, true), 0.04, 10);
            sc.lambda = ON;
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.run().failure());
            assertEquals(r.targets().size(), r.run().done(), r.run().summaryLine("x"));
            assertLambdaRules(r, 8, true);
            assertEquals(0, r.run().dig().aborted, r.run().summaryLine("x"));
            assertEquals(0, r.run().watchdogTrips(), r.run().summaryLine("x"));
        }
    }

    @Test
    void mixedArtWithLambdaBreaksEverythingOnce() {
        for (boolean haste : new boolean[]{true, false}) {
            Scenario sc = new Scenario("ellen-like quarter, Lambda, " + (haste ? "Haste II" : "no Haste"));
            sc.lambda = ON;
            sc.perTick = 1;
            sc.hasteForever = haste;
            sc.blocks = NukeRunTest.quarter(NukeRunTest.ellenLike(3));
            sc.server = s -> {
                s.uplink = 3;
                s.downlink = 3;
                s.jitter = 2;
            };
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertLambdaRules(r, 8);
            assertEquals(8, r.run().perTick(), "no off-look refusals here");
        }
    }

    /** The local 10x speed mode: Paper's mining clock advances 0.1 a client tick, the run scales by it. */
    @Test
    void localTenTimesSpeedBreaksEverythingOnce() {
        for (boolean haste : new boolean[]{false, true}) {
            Scenario sc = lapisRun("lapis, Lambda, 10x " + (haste ? "Haste II" : "no Haste"), haste, ON, false);
            sc.blocks = lapis(10, 0.9, 24);
            sc.serverClock = 0.1;
            sc.server = s -> {
                s.uplink = 1;
                s.downlink = 1;
                s.miningClock = 0.1;
            };
            Result r = sc.run();
            print(r);
            assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
            assertEquals(r.targets().size(), r.run().done());
            assertTrue(r.server().world.isEmpty());
            assertLambdaRules(r, 8);
            assertEquals(0, r.run().dig().delayedTimedOut, r.run().summaryLine("x"));
            assertEquals(0, r.run().dig().refused, r.run().summaryLine("x"));
        }
    }

    @Test
    void neverAStartInTheAir() {
        Scenario sc = lapisRun("lapis, Lambda, hops", false, ON, false);
        sc.blocks = lapis(6, 0.9, 24);
        sc.client = c -> {
            long[] tick = {0};
            c.afterTick = () -> tick[0]++;
            c.airborne = () -> tick[0] % 9 >= 6; // 3 ticks of every 9 in the air
        };
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertLambdaRules(r, 8);
    }

    @Test
    void stopsAtTheToolFloorWithoutBreakingThePickaxe() {
        Scenario sc = lapisRun("lapis, Lambda, pickaxe near the floor", true, ON, false);
        Item[] h = NukeRunTest.kit();
        h[8] = Item.tool("minecraft:netherite_pickaxe", 5, true, FLOOR + 40);
        sc.hotbar = h;
        Result r = sc.run();
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains(BotActions.TOOL_AT_FLOOR), r.run().failure());
        Item pick = r.server().hotbar[8];
        assertTrue(pick != null && pick.remaining() >= FLOOR, "pickaxe " + pick);
        assertEquals(0, r.server().toolsBroken);
        for (DigServer.Destroy d : r.server().destroys) assertTrue(d.usesLeftAfter() >= FLOOR, d.toString());
    }

    @Test
    void protectedBlocksAreNeverTouched() {
        Scenario sc = lapisRun("lapis, Lambda, protected prismarine", false, ON, false);
        Map<Pos, BlockType> m = lapis(7, 0.9, 32);
        Random rnd = new Random(8);
        for (Pos p : new ArrayList<>(m.keySet())) if (rnd.nextDouble() < 0.05) m.put(p, NukeRunTest.PRISMARINE);
        sc.blocks = m;
        sc.protectedIds = Set.of(NukeRunTest.PRISMARINE.id());
        Result r = sc.run();
        assertEquals(TaskStatus.FAILED, r.status());
        assertTrue(r.run().failure().contains("refused (protected)"), r.run().failure());
        for (Pos p : m.keySet()) {
            if (m.get(p) != NukeRunTest.PRISMARINE) continue;
            assertEquals(0, r.server().digsAt.getOrDefault(p, 0), "dug protected " + p);
            assertEquals(0, r.server().digsAt.getOrDefault(DigClient.fakeOf(p), 0), "fake START over protected " + p);
        }
        long left = r.server().world.values().stream().filter(b -> b != NukeRunTest.PRISMARINE).count();
        assertEquals(0, left, "everything else is broken");
    }

    /**
     * Upstream Grim 2.0 (the model) cancelling what FastBreak flags: the STARTs right after a STOP grow its delay
     * balance; the refused blocks switch Lambda's scheme off for the round, and every block still breaks.
     */
    @Test
    void anAntiCheatCancellingItsStartsTurnsItOff() {
        Scenario sc = lapisRun("lapis, Lambda, Grim cancelling", false, ON, false);
        sc.blocks = lapis(9, 0.9, 32);
        sc.server = s -> {
            s.uplink = 3;
            s.downlink = 3;
            s.jitter = 2;
            s.grimCancel = true;
        };
        Result r = sc.run();
        print(r);
        assertEquals(TaskStatus.SUCCESS, r.status(), r.run().failure());
        assertEquals(r.targets().size(), r.run().done());
        assertTrue(r.server().world.isEmpty());
        assertTrue(r.server().grim.fastBreakFlags() > 0, r.sim().rules());
        assertTrue(r.sim().log.stream().anyMatch(l -> l.contains("Lambda's scheme were refused")),
            String.join("\n", r.sim().log));
    }
}
