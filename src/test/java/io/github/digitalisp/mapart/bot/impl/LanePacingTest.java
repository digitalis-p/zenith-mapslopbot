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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane pacing on the simulated platform ({@link LaneSim}, 2b2t-like: acks and slot counts 5 ticks late): the pacer
 * across lanes ({@link AdaptivePacer#newRun}) and the stack switch ({@link PlacePacing#stackSwitch}), with today's
 * defaults ({@link PlacePacing#DEFAULT}) against the previous behaviour ({@link PlacePacing#LEGACY}).
 */
class LanePacingTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final String BLACK = "minecraft:black_carpet";
    private static final int ART_Y = 64;
    private static final Pattern WAITS = Pattern.compile("; (\\d+) t waiting for used-up stacks");
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    private static LaneSim sim(Map<String, Integer> stock, AdaptivePacer pacer) {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = new LaneSim(new LaneSim.Blocks() {
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
        }, -64, -64, ART_Y, pacer, 5);
        sim.confirmDelay = 5; // ack round trip of 5 ticks
        sim.countLag = 5;     // the cache shows the counts as they were before the clicks in flight
        sim.echoes = true;
        return sim;
    }

    /** A 9-wide lane on line x (columns x-4..x+4), rows z0..z1, cells by {@code item}. */
    private static Lane lane(int index, int x, int z0, int z1, java.util.function.BiFunction<Integer, Integer, String> item) {
        List<Placement> ps = new ArrayList<>();
        for (int z = z0; z <= z1; z++) {
            for (int cx = x - 4; cx <= x + 4; cx++) {
                String id = item.apply(cx, z);
                ps.add(new Placement(new Pos(cx, ART_Y, z), BlockSpec.of(id), id));
            }
        }
        return new Lane(index, new Pos(x, ART_Y, z0 - 1), new Pos(x, ART_Y, z1 + 1), List.copyOf(ps));
    }

    private static LaneRun.Params params(LaneSim sim, PlacePacing pacing) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // AIRPLACE, silent looks
        sim.silent = PlaceMode.silentRotation(b);
        return LaneRun.Params.of(b, sim.tileArea()).withPacing(pacing);
    }

    private static int waits(LaneSim sim) {
        int n = 0;
        for (String line : sim.log) {
            Matcher m = WAITS.matcher(line);
            if (m.find()) n += Integer.parseInt(m.group(1));
        }
        return n;
    }

    /**
     * Live 2026-10-11 ("AdaptivePacer[interval=2 in [1,4], streak=0/16" for whole lanes): a lane that ends right after
     * a slow-down leaves its tick of the change in the pacer; the next lane counts its own ticks from 0, so before
     * {@link AdaptivePacer#newRun} it could neither recover nor slow down until its clock passed that tick.
     */
    @Test
    void theNextLaneRecoversFromTheLastLanesSlowDown() {
        for (PlacePacing pacing : new PlacePacing[]{PlacePacing.LEGACY, PlacePacing.DEFAULT}) {
            Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 10_000));
            AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
            LaneSim sim = sim(stock, pacer);
            // lane A: 9 x 40; at its last tick a refusal burst slows the pacer down
            Lane a = lane(0, -60, -62, -23, (x, z) -> WHITE);
            sim.teleport(-59.5, ART_Y, -63.5);
            sim.runLane(a, params(sim, pacing), NONE, 6000);
            assertEquals(360, sim.accepted, String.join("\n", sim.log));
            long laneA = sim.lastTicks;
            if (pacing.policy() == AdaptivePacer.Policy.BURST)
                pacer.onRefusal(laneA - 10, laneA - 5, AdaptivePacer.Refusal.REFUSED);
            pacer.onRefusal(laneA, laneA + 5, AdaptivePacer.Refusal.REFUSED);
            assertEquals(2, pacer.interval(), pacing.policy() + ": lane A ends slowed down " + pacer);
            // lane B: 9 x 8, far fewer ticks than lane A's change happened at
            Lane b = lane(1, -51, -62, -55, (x, z) -> WHITE);
            sim.teleport(-50.5, ART_Y, -63.5);
            sim.runLane(b, params(sim, pacing), NONE, 4000);
            assertEquals(432, sim.accepted, String.join("\n", sim.log));
            assertTrue(sim.lastTicks * 2 < laneA, "lane B is short: " + sim.lastTicks + " vs " + laneA);
            assertEquals(1, pacer.interval(), pacing.policy() + ": recovered within lane B " + pacer);
        }
    }

    /**
     * Stack mode: one item in three hotbar stacks of 16, more in the main inventory. With the stack switch, a stack used
     * up by clicks in flight hands over to the next hotbar stack in the click tick; without it each stack end waits
     * about one round trip for the acks.
     */
    @Test
    void theStackSwitchSkipsTheWaitAtStackEnds() {
        int[] ticks = new int[2];
        int[] waits = new int[2];
        int k = 0;
        for (boolean on : new boolean[]{false, true}) {
            Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 72));
            AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
            LaneSim sim = sim(stock, pacer);
            sim.stackSize = 16;
            sim.hotbarStacksPerItem = 3;
            sim.teleport(-59.5, ART_Y, -63.5);
            PlacePacing pacing = new PlacePacing(AdaptivePacer.Policy.BURST, 16, 100, 8, 1, on, true, false);
            sim.runLane(lane(0, -60, -62, -55, (x, z) -> WHITE), params(sim, pacing), NONE, 4000);
            String why = (on ? "switch" : "wait") + " ticks=" + sim.lastTicks + " wrong=" + sim.wrongClicks + " moves="
                + sim.stackMoves + " skips=" + sim.skipReasons + "\n" + String.join("\n", sim.log);
            assertEquals(72, sim.accepted, why);
            assertEquals(0, sim.wrongClicks, "never a click with an empty hand\n" + why);
            assertTrue(sim.skipReasons.isEmpty(), why);
            ticks[k] = (int) sim.lastTicks;
            waits[k] = waits(sim);
            k++;
        }
        System.out.println("stack switch: off " + ticks[0] + " t (" + waits[0] + " t waiting), on " + ticks[1] + " t ("
            + waits[1] + " t waiting)");
        assertTrue(waits[1] * 2 <= waits[0], "waits " + waits[1] + " vs " + waits[0]);
        assertTrue(ticks[1] < ticks[0], "ticks " + ticks[1] + " vs " + ticks[0]);
    }

    /**
     * Twelve colours in a 9-wide lane, eight hotbar slots: a move from the main inventory replaces the stack the cells
     * left need last ({@link PlacePacing#belady}) instead of the next one round robin.
     */
    @Test
    void beladyMovesLessThanRoundRobin() {
        String[] colours = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue"};
        int[] moves = new int[2];
        long[] ticks = new long[2];
        for (int k = 0; k < 2; k++) {
            boolean belady = k == 1;
            Map<String, Integer> stock = new HashMap<>();
            for (String c : colours) stock.put("minecraft:" + c + "_carpet", 1000);
            AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
            LaneSim sim = sim(stock, pacer);
            sim.teleport(-59.5, ART_Y, -63.5);
            java.util.Random rnd = new java.util.Random(5);
            Map<Pos, String> art = new HashMap<>();
            Lane lane = lane(0, -60, -62, -23, (x, z) -> art.computeIfAbsent(new Pos(x, ART_Y, z),
                p -> "minecraft:" + colours[(Math.floorMod(x, 3) + 3 * Math.floorMod(z / 4 + rnd.nextInt(2), 4))]
                    + "_carpet"));
            PlacePacing pacing = new PlacePacing(AdaptivePacer.Policy.BURST, 16, 100, 8, 1, true, belady, false);
            sim.runLane(lane, params(sim, pacing), NONE, 20_000);
            String why = (belady ? "Belady" : "round robin") + " ticks=" + sim.lastTicks + " moves="
                + sim.inventoryMoves + " skips=" + sim.skipReasons + "\n" + String.join("\n", sim.log);
            assertEquals(360, sim.accepted, why);
            assertEquals(0, sim.wrongClicks, why);
            moves[k] = sim.inventoryMoves;
            ticks[k] = sim.lastTicks;
        }
        System.out.println("12 colors, 360 cells: round robin " + moves[0] + " moves " + ticks[0] + " t, Belady "
            + moves[1] + " moves " + ticks[1] + " t");
        assertTrue(moves[1] < moves[0], "moves " + moves[1] + " vs " + moves[0]);
        assertTrue(ticks[1] <= ticks[0], "ticks " + ticks[1] + " vs " + ticks[0]);
    }

    /** One run of a 9 x 40 lane of {@code colours} carpets: ticks, walk starts; asserts a clean run. */
    private static long[] walkRun(boolean paced, int startLoss, int colours, long seed) {
        String[] ids = {"minecraft:white_carpet", "minecraft:black_carpet", "minecraft:red_carpet",
            "minecraft:blue_carpet", "minecraft:green_carpet", "minecraft:yellow_carpet", "minecraft:lime_carpet",
            "minecraft:cyan_carpet"};
        Map<String, Integer> stock = new HashMap<>();
        for (String id : ids) stock.put(id, 10_000);
        AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
        LaneSim sim = sim(stock, pacer);
        sim.walkStartClickLoss = startLoss;
        sim.teleport(-59.5, ART_Y, -63.5);
        java.util.Random rnd = new java.util.Random(seed);
        Map<Pos, String> art = new HashMap<>();
        Lane lane = lane(0, -60, -62, -23, (x, z) -> art.computeIfAbsent(new Pos(x, ART_Y, z),
            p -> ids[rnd.nextInt(colours)]));
        PlacePacing pacing = new PlacePacing(AdaptivePacer.Policy.BURST, 16, 100, 8, 1, true, true, paced);
        sim.runLane(lane, params(sim, pacing), NONE, 20_000);
        String log = String.join("\n", sim.log);
        String why = (paced ? "paced" : "stop-go") + " loss " + startLoss + " ticks=" + sim.lastTicks + " starts="
            + sim.walkStarts + " skips=" + sim.skipReasons + "\n" + log;
        assertEquals(360, sim.accepted, why);
        assertEquals(0, sim.wrongClicks, why);
        assertEquals(0, sim.boxRejects, "never into the bot's own box\n" + why);
        assertEquals(0, sim.farRejects, "never beyond the server's airplace range\n" + why);
        assertEquals(1, sim.maxPlacementsPerTick, why);
        assertTrue(sim.skipReasons.isEmpty(), why);
        assertTrue(log.contains("0 skipped of 360"), why);
        assertTrue(!log.contains("stand spots"), "nothing left to stand spots\n" + why);
        assertEquals(paced, log.contains("; paced walk, "), why);
        return new long[]{sim.lastTicks, sim.walkStarts};
    }

    /**
     * The paced walk ({@link PlacePacing#pacedWalk}) against stop and go, 9 x 40 lanes of 1, 4 and 8 carpet colours,
     * 3 seeds each, acks 5 ticks late: the same cells, all placed, none into the box or beyond the airplace range, one
     * placement per tick, nothing left to stand spots; at least 30 % fewer walk starts and never slower. With one
     * click-less tick per walk start (a stand-in for the routing study's 2b2t fit of about 0.85) at least 3 % faster.
     * Measured (ticks, stop-go → paced): 0 lost ticks 1116 → 1098, 1141 → 1118, 1129 → 1119; 1 lost tick
     * 1452 → 1245, 1277 → 1217, 1311 → 1232. Walk starts 312 → 147, 141 → 91, 172 → 111.
     */
    @Test
    void thePacedWalkStartsLessAndIsNoSlower() {
        StringBuilder sb = new StringBuilder();
        for (int colours : new int[]{1, 4, 8}) {
            for (int loss : new int[]{0, 1}) {
                long[] stop = new long[2];
                long[] paced = new long[2];
                for (long seed = 1; seed <= 3; seed++) {
                    long[] a = walkRun(false, loss, colours, seed);
                    long[] b = walkRun(true, loss, colours, seed);
                    for (int i = 0; i < 2; i++) {
                        stop[i] += a[i];
                        paced[i] += b[i];
                    }
                }
                String what = String.format("%d color(s), %d lost tick(s) per walk start: stop-go %d t %d starts,"
                    + " paced %d t %d starts", colours, loss, stop[0], stop[1], paced[0], paced[1]);
                sb.append(what).append('\n');
                assertTrue(paced[1] * 10 <= stop[1] * 7, "fewer walk starts: " + what);
                assertTrue(paced[0] <= stop[0], "never slower: " + what);
                if (loss > 0) assertTrue(paced[0] * 100 <= stop[0] * 97, "faster with lost ticks per start: " + what);
            }
        }
        System.out.println(sb);
    }

    /** Two items, single stacks, moves from the main inventory: the defaults never click an empty hand either. */
    @Test
    void stackEndsWithMovesStayClean() {
        for (PlacePacing pacing : new PlacePacing[]{PlacePacing.LEGACY, PlacePacing.DEFAULT}) {
            Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 36, BLACK, 36));
            AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
            LaneSim sim = sim(stock, pacer);
            sim.stackSize = 10;
            sim.teleport(-59.5, ART_Y, -63.5);
            sim.runLane(lane(0, -60, -62, -55, (x, z) -> (x + z) % 2 == 0 ? WHITE : BLACK), params(sim, pacing), NONE,
                6000);
            String why = pacing.policy() + " ticks=" + sim.lastTicks + " wrong=" + sim.wrongClicks + " moves="
                + sim.stackMoves + " skips=" + sim.skipReasons + "\n" + String.join("\n", sim.log);
            assertEquals(72, sim.accepted, why);
            assertEquals(0, sim.wrongClicks, why);
            assertTrue(sim.stackMoves >= 6, "stacks moved in from the main inventory\n" + why);
            assertTrue(sim.skipReasons.isEmpty(), why);
        }
    }
}
