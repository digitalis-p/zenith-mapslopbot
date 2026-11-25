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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast placement ({@code build.placeClicksPerTick}, {@link PlacePacing#clicksPerTick}) on the
 * simulated platform ({@link LaneSim}, 2b2t-like: acks and slot counts 4 ticks late, the paced walk as live), with
 * Paper's click limiter emulated in ticks ({@link LaneSim#paperWindowTicks}: 9 clicks per window of 6 ticks, the
 * window restarting at the first click after it ended).
 */
class FastPlacementTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final String[] COLOURS = {"minecraft:white_carpet", "minecraft:black_carpet",
        "minecraft:red_carpet", "minecraft:blue_carpet"};
    private static final String WOOL = "minecraft:white_wool";
    private static final int ART_Y = 64;
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    /** One lane run: ticks, placed, and the sim's counters. */
    private record Run(long ticks, LaneSim sim) {
        double perSecond() {
            return sim.accepted * 20.0 / ticks;
        }
    }

    private static LaneSim sim(Map<String, Integer> stock) {
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
        }, -64, -64, ART_Y, new AdaptivePacer(1, 1, 4), 7);
        sim.confirmDelay = 4; // live ack p50 3.4 ticks
        sim.countLag = 4;     // the cache shows the counts as they were before the clicks in flight
        sim.echoes = true;
        sim.paperWindowTicks = 6;
        sim.paperWindowMax = 9;
        return sim;
    }

    /** A 9-wide lane on line x (columns x-4..x+4), rows z0..z1, cells by {@code item}. */
    private static Lane lane(int x, int z0, int z1, BiFunction<Integer, Integer, String> item) {
        List<Placement> ps = new ArrayList<>();
        for (int z = z0; z <= z1; z++) {
            for (int cx = x - 4; cx <= x + 4; cx++) {
                String id = item.apply(cx, z);
                ps.add(new Placement(new Pos(cx, ART_Y, z), BlockSpec.of(id), id));
            }
        }
        return new Lane(0, new Pos(x, ART_Y, z0 - 1), new Pos(x, ART_Y, z1 + 1), List.copyOf(ps));
    }

    private static LaneRun.Params params(LaneSim sim, int clicksPerTick, int windowTicks, int windowMax) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // AIRPLACE, silent looks, the live paced walk
        b.pacedLaneWalk = true;
        b.placeAdaptiveWindow = false; // existing tests exercise the fixed window
        sim.silent = PlaceMode.silentRotation(b);
        PlacePacing pacing = PlacePacing.of(b).withClicksPerTick(clicksPerTick, windowTicks, windowMax);
        return LaneRun.Params.of(b, sim.tileArea()).withPacing(pacing);
    }

    private static Run run(Lane lane, Map<String, Integer> stock, int stackSize, int k, int windowTicks, int windowMax) {
        LaneSim sim = sim(stock);
        sim.stackSize = stackSize;
        sim.slidingTicks = windowTicks;
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        sim.runLane(lane, params(sim, k, windowTicks, windowMax), NONE, 20_000);
        return new Run(sim.lastTicks, sim);
    }

    private static Map<String, Integer> stock(String... items) {
        Map<String, Integer> m = new HashMap<>();
        for (String i : items) m.put(i, 100_000);
        return m;
    }

    /** Fixed live budget, with actual standing/cap reasons printed beside the throughput. */
    @Test
    void pacedStandingProfiles() {
        for (boolean mixed : List.of(false, true)) {
            Run r = run(lane(-60, -62, 57, (x, z) -> mixed ? COLOURS[Math.floorMod(z, 4)] : WHITE),
                stock(COLOURS), 64, 2, 7, 9);
            String line = r.sim.log.stream().filter(l -> l.contains("standing ticks ")).findFirst().orElseThrow();
            System.out.printf("standing profile %s: %d clicks / %d ticks = %.4f clicks/tick (%.2f cells/s)%n%s%n",
                mixed ? "four colors" : "one color", r.sim.clicks, r.ticks,
                (double) r.sim.clicks / r.ticks, r.perSecond(), line);
            assertEquals(1080, r.sim.accepted, line);
            assertEquals(0, r.sim.paperDrops, line);
            assertTrue(r.sim.maxPlacementsPerTick <= 2, line);
            assertTrue(r.sim.maxPerSlidingWindow <= 9, line);
            assertStandingAccounting(line);
            assertTrue(reasonCount(line, "standing ticks", "window full") > 0, line);
            assertTrue(reasonCount(line, "cap holds", "ack") > 0, line);
            if (!mixed) assertTrue(reasonCount(line, "standing ticks", "used-up stack") > 0, line);
        }
    }

    static int reasonCount(String line, String section, String reason) {
        String part = line.substring(line.indexOf(section)).split(";", 2)[0];
        var match = Pattern.compile("(\\d+) " + Pattern.quote(reason) + "(?:,|$)").matcher(part);
        assertTrue(match.find(), line);
        return Integer.parseInt(match.group(1));
    }

    /** Counts are exclusive, and cap evaluations are counted separately from actual keys-up ticks. */
    static void assertStandingAccounting(String line) {
        var standing = Pattern.compile("standing ticks (\\d+), (\\d+) without a click: ([^;]+)").matcher(line);
        assertTrue(standing.find(), line);
        int ticks = Integer.parseInt(standing.group(1));
        int idle = Integer.parseInt(standing.group(2));
        assertTrue(idle <= ticks, line);
        var reasons = Pattern.compile("(?:^|, )(\\d+) ").matcher(standing.group(3));
        int sum = 0;
        while (reasons.find()) sum += Integer.parseInt(reasons.group(1));
        assertEquals(idle, sum, line);
        var held = Pattern.compile("paced walk, (\\d+) ticks held by the cap").matcher(line);
        assertTrue(held.find(), line);
        String caps = line.substring(line.indexOf("cap holds: ")).split(";", 2)[0].substring("cap holds: ".length());
        reasons = Pattern.compile("(?:^|, )(\\d+) ").matcher(caps);
        sum = 0;
        while (reasons.find()) sum += Integer.parseInt(reasons.group(1));
        assertEquals(Integer.parseInt(held.group(1)), sum, line);
    }

    @Test
    void standingAckAndRetryWaitsAreDistinctFromWindowAndStackWaits() {
        for (boolean retry : List.of(false, true)) {
            Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
            LaneSim sim = sim(stock(WHITE));
            sim.confirmDelay = retry ? 4 : 16;
            sim.countLag = sim.confirmDelay;
            if (retry) {
                java.util.Set<Pos> refused = new java.util.HashSet<>();
                sim.anticheat = refused::add; // refuse each cell once; its second click succeeds
            }
            sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
            sim.runLane(lane, params(sim, 2, 7, 9), NONE, 20_000);
            String line = sim.log.stream().filter(l -> l.contains("standing ticks ")).findFirst().orElseThrow();
            assertEquals(lane.placements().size(), sim.accepted, line);
            assertStandingAccounting(line);
            assertTrue(reasonCount(line, "standing ticks", retry ? "cell retry" : "cell ack") > 0, line);
            assertTrue(reasonCount(line, "cap holds", retry ? "retry" : "ack") > 0, line);
        }
    }

    @Test
    void offByDefault() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals(1, b.placeClicksPerTick);
        PlacePacing p = PlacePacing.of(b);
        assertTrue(b.placeHotbarStacks && p.hotbarStacks());
        assertTrue(b.placeAirPlaceBatch && p.airplaceBatch());
        assertTrue(!p.multiClick(), "fast placement is off by default");
        assertEquals(1.0, p.clickRate());
        assertEquals(PlacePacing.DEFAULT, PlacePacing.of(b), "the defaults are the pacing before fast placement");
        Run r = run(lane(-60, -62, 57, (x, z) -> WHITE), stock(WHITE), 0, 1, 7, 9);
        assertEquals(1080, r.sim.accepted, String.join("\n", r.sim.log));
        assertEquals(1, r.sim.maxPlacementsPerTick, "one click per tick");
        assertEquals(0, r.sim.multiClickTicks);
    }

    /**
     * One colour, a 9 × 120 lane: two clicks per tick within the window of 9 per 6 ticks place every cell faster than one
     * per tick, never more than 9 clicks in any 6 ticks, so the emulated limiter drops none.
     */
    @Test
    void twoPerTickWithinTheWindowIsFasterAndNeverDropped() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        Run one = run(lane, stock(WHITE), 0, 1, 7, 9);
        Run two = run(lane, stock(WHITE), 0, 2, 6, 9);
        Run safe = run(lane, stock(WHITE), 0, 2, 7, 9);
        Run three = run(lane, stock(WHITE), 0, 3, 6, 9);
        System.out.printf("fast placement, 1 color: 1/tick %d t (%.1f/s), 2/tick 9 per 6 t %d t (%.1f/s), "
                + "2/tick 9 per 7 t %d t (%.1f/s), 3/tick 9 per 6 t %d t (%.1f/s)%n", one.ticks, one.perSecond(),
            two.ticks, two.perSecond(), safe.ticks, safe.perSecond(), three.ticks, three.perSecond());
        assertTrue(three.sim.maxPerSlidingWindow <= 9, "at most 9 in any 6 ticks: " + three.sim.maxPerSlidingWindow);
        for (Run r : List.of(one, two, safe, three)) {
            assertEquals(1080, r.sim.accepted, String.join("\n", r.sim.log));
            assertEquals(0, r.sim.paperDrops, "the limiter dropped none: " + String.join("\n", r.sim.log));
        }
        assertEquals(2, two.sim.maxPlacementsPerTick);
        assertTrue(two.sim.maxPerSlidingWindow <= 9, "at most 9 in any 6 ticks: " + two.sim.maxPerSlidingWindow);
        assertTrue(safe.sim.maxPerSlidingWindow <= 9, "at most 9 in any 7 ticks: " + safe.sim.maxPerSlidingWindow);
        assertTrue(two.ticks < one.ticks * 0.85, "two per tick is faster: " + two.ticks + " vs " + one.ticks);
        assertTrue(safe.ticks < one.ticks * 0.9, "also at 9 per 7 ticks: " + safe.ticks + " vs " + one.ticks);
        assertTrue(two.perSecond() <= 30.0 + 1e-9, "never above the limiter's 30/s: " + two.perSecond());
    }

    /** A window wider than Paper's (12 per 6 ticks) meets the limiter: drops are retried without slowing the interval. */
    @Test
    void aWindowAboveTheLimiterIsDroppedWithoutSlowingThePacer() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        Run over = run(lane, stock(WHITE), 0, 2, 6, 12);
        assertTrue(over.sim.paperDrops > 0, "the limiter drops clicks above 9 per window");
        assertEquals(1080, over.sim.accepted, "dropped clicks are retried: " + String.join("\n", over.sim.log));
        assertEquals(1, over.sim.pacer.interval());
        assertTrue(over.sim.pacer.fastPlacementOn(), "drops do not latch");
    }

    /**
     * The latch: an anti-cheat that cancels every click sharing a tick (Grim's MultiPlace from its fifth flag: an ack of
     * exactly the click's sequence, no block update) turns fast placement off after {@link AdaptivePacer#SHARED_FAILS}
     * such refusals; the lane still places every cell, one per tick from then on.
     */
    @Test
    void refusedSharedClicksTurnFastPlacementOff() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        long[] lastTick = {-1};
        int[] inTick = {0};
        sim.anticheat = p -> {
            long t = sim.now();
            if (t != lastTick[0]) {
                lastTick[0] = t;
                inTick[0] = 0;
            }
            return ++inTick[0] > 1; // the second and later place of a tick
        };
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeClicksPerTick = 2;
        Random jitter = new Random(6);
        sim.clickJitterTicks = seq -> jitter.nextInt(3);
        sim.silent = PlaceMode.silentRotation(b);
        sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000);
        assertEquals(1080, sim.accepted, String.join("\n", sim.log));
        assertTrue(!sim.pacer.fastPlacementOn(), "the latch tripped");
        // the clicks in flight while the first refusals come back (one ack round trip) are refused too, no more
        assertTrue(sim.anticheatCancels >= AdaptivePacer.SHARED_FAILS && sim.anticheatCancels <= 12,
            "cancelled until the latch: " + sim.anticheatCancels);
        System.out.println("latch: " + sim.anticheatCancels + " shared clicks cancelled before fast placement went off");
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("fast placement off: 3 of the last")), String.join("\n", sim.log));
    }

    /** Full blocks never share a tick (they wall the walk and hide cells: one at a time, as before). */
    @Test
    void fullBlocksStayOnePerTick() {
        Lane lane = lane(-60, -62, 21, (x, z) -> (x + z) % 3 == 0 ? WOOL : WHITE);
        Run r = run(lane, stock(WHITE, WOOL), 0, 3, 6, 9);
        assertTrue(r.sim.accepted >= lane.placements().size() - 12, String.join("\n", r.sim.log));
        assertEquals(1, r.sim.maxSolidsPerTick, "never two full blocks in a tick");
        assertTrue(r.sim.multiClickTicks > 0, "the carpets still go two and three at a time");
        assertTrue(r.sim.maxPlacementsPerTick <= 3);
    }

    /** Four colours in 64-stacks (hotbar moves and stack ends, as live): still faster, and every click from the held slot. */
    @Test
    void fourColoursWithStacks() {
        Random rnd = new Random(5);
        Map<Long, String> art = new HashMap<>();
        BiFunction<Integer, Integer, String> pick = (x, z) -> art.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL),
            k -> COLOURS[rnd.nextInt(COLOURS.length)]);
        Lane lane = lane(-60, -62, 57, pick);
        Run one = run(lane, stock(COLOURS), 64, 1, 7, 9);
        Run two = run(lane, stock(COLOURS), 64, 2, 6, 9);
        Run safe = run(lane, stock(COLOURS), 64, 2, 7, 9);
        Run three = run(lane, stock(COLOURS), 64, 3, 6, 9);
        System.out.printf("fast placement, 4 colors, 64-stacks: 1/tick %d t (%.1f/s), 2/tick 9 per 6 t %d t (%.1f/s), "
                + "%d ticks with 2 clicks; 2/tick 9 per 7 t %d t (%.1f/s); 3/tick 9 per 6 t %d t (%.1f/s)%n", one.ticks,
            one.perSecond(), two.ticks, two.perSecond(), two.sim.multiClickTicks, safe.ticks, safe.perSecond(),
            three.ticks, three.perSecond());
        assertEquals(1080, three.sim.accepted, String.join("\n", three.sim.log));
        assertEquals(0, three.sim.paperDrops);
        assertEquals(1080, one.sim.accepted, String.join("\n", one.sim.log));
        assertEquals(1080, two.sim.accepted, String.join("\n", two.sim.log));
        assertEquals(0, two.sim.paperDrops);
        assertEquals(0, two.sim.wrongClicks, "every click had its item in the hand");
        for (String line : one.sim.log) if (line.contains(" placed, ")) System.out.println("1/tick: " + line);
        for (String line : two.sim.log) if (line.contains(" placed, ")) System.out.println("2/tick: " + line);
        assertTrue(two.ticks < one.ticks, "faster: " + two.ticks + " vs " + one.ticks);
    }

    /** The pacer above one tick (here its floor is 2) turns fast placement off by itself. */
    @Test
    void aSlowPacerSendsOnePerTick() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeIntervalMinTicks = 2;
        b.placeClicksPerTick = 3;
        b.placeClickWindowTicks = 6;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 10_000);
        assertEquals(lane.placements().size(), sim.accepted, String.join("\n", sim.log));
        assertEquals(1, sim.maxPlacementsPerTick, "interval 2: one click at most per tick");
        String line = sim.log.stream().filter(l -> l.contains("standing ticks ")).findFirst().orElseThrow();
        assertStandingAccounting(line);
        assertTrue(reasonCount(line, "standing ticks", "pacer interval") > 0, line);
    }

    /** LOOK mode: every click needs its own rotation, so one per tick. */
    @Test
    void lookModeStaysOnePerTick() {
        List<Placement> ps = new ArrayList<>();
        for (int z = -62; z <= -40; z++) {
            for (int cx = -62; cx <= -58; cx++) ps.add(new Placement(new Pos(cx, ART_Y, z), BlockSpec.of(WHITE), WHITE));
        }
        Lane lane = new Lane(0, new Pos(-60, ART_Y, -63), new Pos(-60, ART_Y, -39), List.copyOf(ps));
        LaneSim sim = sim(stock(WHITE));
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeMode = "LOOK";
        b.placeClicksPerTick = 3;
        b.placeClickWindowTicks = 6;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 10_000);
        assertEquals(lane.placements().size(), sim.accepted, String.join("\n", sim.log));
        assertEquals(1, sim.maxPlacementsPerTick);
    }
    private static Run jitterRun(Lane lane, boolean adaptive, long seed, int stackSize) {
        return jitterRun(lane, adaptive, seed, stackSize, false);
    }

    private static Run jitterRun(Lane lane, boolean adaptive, long seed, int stackSize, boolean uniform) {
        LaneSim sim = sim(stock(COLOURS));
        sim.stackSize = stackSize;
        Random random = new Random(seed);
        sim.clickJitterTicks = seq -> {
            // Most clicks arrive with the normal one-tick delay; occasional early/late arrivals span 0..2.
            if (uniform) return random.nextInt(3);
            int n = random.nextInt(100);
            return n < 3 ? 0 : n >= 97 ? 2 : 1;
        };
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeClicksPerTick = 2;
        b.placeAdaptiveWindow = adaptive;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS,
            sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000), String.join("\n", sim.log));
        return new Run(sim.lastTicks, sim);
    }

    @Test
    void adaptiveWindowBeatsFixedSevenWithArrivalJitter() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        long fixedTicks = 0, adaptiveTicks = 0;
        int fixedDrops = 0, adaptiveDrops = 0, fixedClicks = 0, adaptiveClicks = 0;
        for (int seed = 1; seed <= 10; seed++) {
            Run fixed = jitterRun(lane, false, seed, 0);
            Run adaptive = jitterRun(lane, true, seed, 0);
            for (Run r : List.of(fixed, adaptive)) {
                assertEquals(1080, r.sim.accepted);
                assertTrue(r.sim.skipReasons.isEmpty());
                assertTrue(r.sim.pacer.fastPlacementOn(), "Paper drops must not latch");
                assertEquals(1, r.sim.pacer.interval(), "Paper drops must not slow the interval");
                for (int count : r.sim.jitterCounts) assertTrue(count > 0, "all jitter values exercised");
            }
            assertTrue(adaptive.sim.log.stream().anyMatch(l -> l.contains("click window 9 per 6 t")));
            fixedTicks += fixed.ticks;
            adaptiveTicks += adaptive.ticks;
            fixedDrops += fixed.sim.paperDrops;
            adaptiveDrops += adaptive.sim.paperDrops;
            fixedClicks += fixed.sim.clicks;
            adaptiveClicks += adaptive.sim.clicks;
        }
        double fixedRate = 10800 * 20.0 / fixedTicks;
        double adaptiveRate = 10800 * 20.0 / adaptiveTicks;
        double dropRate = adaptiveDrops / (double) adaptiveClicks;
        System.out.printf("arrival limiter (10 seeds, 0..2 t jitter): fixed 9/7 %.2f cells/s, %.2f %% drops; "
                + "adaptive %.2f cells/s, %.2f %% drops%n", fixedRate, fixedDrops * 100.0 / fixedClicks,
            adaptiveRate, dropRate * 100);
        assertTrue(adaptiveRate >= 25, "target: " + adaptiveRate);
        assertTrue(adaptiveRate > fixedRate * 1.04, "adaptive gain: " + fixedRate + " -> " + adaptiveRate);
        assertTrue(dropRate <= 0.02, "aggregate drops: " + dropRate);
    }

    @Test
    void mixedColoursWithStacksAndArrivalJitter() {
        Random random = new Random(5);
        Lane lane = lane(-60, -62, 57, (x, z) -> COLOURS[random.nextInt(4)]);
        Run fixed = jitterRun(lane, false, 5, 64);
        Run adaptive = jitterRun(lane, true, 5, 64);
        System.out.printf("arrival limiter (4 colors, 64-stacks): fixed 9/7 %.2f cells/s; adaptive %.2f cells/s, %.2f %% drops%n",
            fixed.perSecond(), adaptive.perSecond(), adaptive.sim.paperDrops * 100.0 / adaptive.sim.clicks);
        assertEquals(1080, adaptive.sim.accepted);
        assertEquals(0, adaptive.sim.wrongClicks);
        assertTrue(adaptive.sim.pacer.fastPlacementOn());
        assertTrue(adaptive.ticks < fixed.ticks);
        assertTrue(adaptive.perSecond() >= 25);
    }

    @Test
    void uniformJitterWidensInsteadOfLatchingOrSlowing() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        Run fixed = jitterRun(lane, false, 42, 0, true);
        Run adaptive = jitterRun(lane, true, 42, 0, true);
        System.out.printf("arrival limiter (uniform 0..2 t jitter): fixed 9/7 %.2f cells/s, %.2f %% drops; "
                + "adaptive %.2f cells/s, %.2f %% drops%n", fixed.perSecond(), fixed.sim.paperDrops * 100.0 / fixed.sim.clicks,
            adaptive.perSecond(), adaptive.sim.paperDrops * 100.0 / adaptive.sim.clicks);
        assertEquals(1080, adaptive.sim.accepted);
        assertEquals(1, adaptive.sim.pacer.interval());
        assertTrue(adaptive.sim.pacer.fastPlacementOn());
        assertTrue(adaptive.sim.log.stream().anyMatch(l -> l.contains("click window 9 per 8 t")),
            String.join("\n", adaptive.sim.log));
        assertTrue(adaptive.sim.pacer.clickWindow().ticks() <= 8);
    }

    @Test
    void droppedCellRetriesAsSoonAsLaterAckClassifiesIt() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        boolean[] first = {true};
        sim.limiter = p -> {
            if (!first[0]) return false;
            first[0] = false;
            return true;
        };
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        sim.runLane(lane, params(sim, 2, 7, 9), NONE, 20_000);
        LaneSim.SentClick dropped = sim.sentClicks.getFirst();
        LaneSim.SentClick retry = sim.sentClicks.stream().skip(1).filter(c -> c.cell().equals(dropped.cell()))
            .findFirst().orElseThrow();
        long coveredAt = sim.sentClicks.get(1).tick() + sim.confirmDelay;
        assertTrue(retry.tick() >= coveredAt + PlaceTracker.REJECT_GRACE_TICKS);
        assertTrue(retry.tick() <= coveredAt + PlaceTracker.REJECT_GRACE_TICKS + 1,
            "retry at the next window opening, without the RTT retry delay");
        assertEquals(1, sim.limiterDrops);
        assertEquals(lane.placements().size(), sim.accepted);
        assertEquals(1, sim.pacer.interval());
        assertTrue(sim.pacer.fastPlacementOn());
    }

    @Test
    void blockUpdateRefusalsDoNotFeedExactAckLatch() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        sim.refuse = p -> sim.sentClicks.size() > 1
            && sim.sentClicks.get(sim.sentClicks.size() - 2).tick() == sim.now();
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeClicksPerTick = 2;
        b.placeIntervalMaxTicks = 1;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000);
        assertTrue(sim.refusals >= 3);
        assertTrue(sim.pacer.fastPlacementOn(), "block updates are not the exact-ack cancellation signature");
        assertTrue(sim.log.stream().noneMatch(l -> l.contains("fast placement off:")));
    }

    @Test
    void singleClickKillSwitchIgnoresAllWindowSettingsIncludingWalkRate() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        Run baseline = run(lane, stock(WHITE), 0, 1, 7, 9);
        Run restricted = run(lane, stock(WHITE), 0, 1, 100, 1);
        assertEquals(lane.placements().size(), restricted.sim.accepted);
        assertEquals(baseline.ticks, restricted.ticks);
        assertEquals(1, restricted.sim.maxPlacementsPerTick);
    }

    private static Run hotbarRun(Lane lane, boolean layout, boolean batch) {
        LaneSim sim = sim(stock(COLOURS));
        sim.stackSize = 64;
        sim.moveRequestTicks = 2;
        sim.moveFenceTicks = 6;
        sim.staleMoveReports = true;
        sim.slidingTicks = 7;
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeClicksPerTick = 2;
        b.placeAdaptiveWindow = false;
        b.placeHotbarStacks = layout;
        b.placeAirPlaceBatch = batch;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS,
            sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000), String.join("\n", sim.log));
        return new Run(sim.lastTicks, sim);
    }

    @Test
    void laneStacksReducePlacementMovesAndBatchSavesTwoPacketsPerExtraClick() {
        Random random = new Random(5);
        Lane lane = lane(-60, -62, 57, (x, z) -> {
            int n = random.nextInt(100);
            return COLOURS[n < 50 ? 0 : n < 75 ? 1 : n < 90 ? 2 : 3];
        });
        Run before = hotbarRun(lane, false, false);
        Run layout = hotbarRun(lane, true, false);
        Run batch = hotbarRun(lane, false, true);
        Run after = hotbarRun(lane, true, true);
        for (Run r : List.of(before, layout, batch, after)) {
            assertEquals(1080, r.sim.accepted);
            assertEquals(0, r.sim.wrongClicks);
            assertEquals(0, r.sim.paperDrops);
            assertEquals(0, r.sim.moveRuleViolations, "request execution and stale target stay fenced");
            assertTrue(r.sim.maxPlacementsPerTick <= 2);
            assertTrue(r.sim.maxPerSlidingWindow <= 9);
        }
        assertEquals(0, before.sim.layoutMoves, "layout kill switch");
        assertEquals(2, after.sim.layoutStacks.get(COLOURS[0]));
        assertEquals(2, after.sim.layoutStacks.get(COLOURS[1]));
        assertTrue(after.sim.inventoryMoves - after.sim.layoutMoves < before.sim.inventoryMoves,
            "fewer moves during placement; preparation is counted separately");
        assertEquals(before.sim.sentClicks, batch.sim.sentClicks, "batching does not change click timing or targets");
        assertEquals(layout.sim.sentClicks, after.sim.sentClicks);
        for (Run r : List.of(before, layout)) assertEquals(r.sim.clicks * 4, r.sim.airPackets.size());
        for (Run r : List.of(batch, after)) {
            int extra = r.sim.clicks - (int) r.sim.sentClicks.stream().map(LaneSim.SentClick::tick).distinct().count();
            assertTrue(extra > 0);
            assertEquals(r.sim.clicks * 4 - extra * 2, r.sim.airPackets.size());
            Map<Long, List<LaneSim.WirePacket>> ticks = new java.util.TreeMap<>();
            int sequence = 0;
            for (var packet : r.sim.airPackets) if (packet.kind().equals("use"))
                assertEquals(++sequence, packet.sequence(), "one increasing prediction sequence per click");
            for (var packet : r.sim.airPackets) ticks.computeIfAbsent(packet.tick(), k -> new ArrayList<>()).add(packet);
            for (var packets : ticks.values()) {
                assertEquals("swap", packets.getFirst().kind());
                assertEquals("swap", packets.getLast().kind());
                assertEquals(2, packets.stream().filter(p -> p.kind().equals("swap")).count());
                for (int i = 1; i < packets.size() - 1; i += 2) {
                    assertEquals("use", packets.get(i).kind());
                    assertEquals("swing", packets.get(i + 1).kind());
                    assertEquals(packets.getFirst().slot(), packets.get(i).slot());
                }
            }
        }
        System.out.printf("hotbar LaneSim (1080 cells, 4 colors, 64 stacks, 2 request/6 fence ticks): "
                + "placement moves %d -> %d; preparation moves %d -> %d; total moves %d -> %d; "
                + "clicks/tick %.3f -> %.3f; packets/extra click 4 -> 2; ticks %d -> %d%n",
            before.sim.inventoryMoves, after.sim.inventoryMoves - after.sim.layoutMoves,
            before.sim.layoutMoves, after.sim.layoutMoves, before.sim.inventoryMoves, after.sim.inventoryMoves,
            before.sim.clicks / (double) before.ticks, after.sim.clicks / (double) after.ticks,
            before.ticks, after.ticks);
    }

    private static Run subTickRun(Lane lane, boolean enabled, int jitterMillis, long seed, int stacks,
                                  int dispatchDelay) {
        LaneSim sim = sim(stock(COLOURS));
        sim.stackSize = stacks;
        sim.millisecondArrivals = true;
        if (!enabled) sim.networkLatencyMillis = 0;
        Random random = new Random(seed);
        sim.clickJitterMillis = seq -> random.nextInt(jitterMillis + 1);
        sim.dispatchDelayMillis = dispatchDelay;
        sim.moveRequestTicks = 2;
        sim.moveFenceTicks = 6;
        sim.staleMoveReports = true;
        var params = params(sim, 2, 7, 9);
        params = params.withPacing(params.pacing().withSubTickDispatch(enabled, 310));
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS, sim.runLane(lane, params, NONE, 20_000),
            String.join("\n", sim.log));
        assertEquals(lane.placements().size(), sim.accepted);
        assertEquals(0, sim.wrongClicks);
        assertEquals(0, sim.moveRuleViolations);
        assertWireOrder(sim);
        assertTrue(sim.skipReasons.isEmpty());
        int sequence = 0;
        long previous = -1;
        for (var click : sim.timedClicks) {
            assertEquals(++sequence, click.sequence());
            assertTrue(click.sendMillis() >= previous);
            previous = click.sendMillis();
        }
        int sent = sim.clicks;
        sim.advanceMillis(sim.lastTicks * 50 + 1000);
        assertEquals(sent, sim.clicks, "no scheduled click after completion");
        return new Run(sim.lastTicks, sim);
    }

    @Test
    void subTickWalkedRateWithMillisecondArrivalJitter() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        for (int jitter : List.of(1, 10)) {
            long ticks = 0;
            int drops = 0, clicks = 0;
            for (int seed = 1; seed <= 10; seed++) {
                Run r = subTickRun(lane, true, jitter, seed, 0, 0);
                ticks += r.ticks;
                drops += r.sim.paperDrops;
                clicks += r.sim.clicks;
                assertTrue(r.sim.walked > 100, "measures the walked lane");
                assertTrue(r.sim.timedClicks.stream().anyMatch(c -> c.sendMillis() % 50 != 0));
                assertTrue(r.sim.pacer.fastPlacementOn());
                assertEquals(1, r.sim.pacer.interval());
            }
            double rate = 10800 * 20.0 / ticks;
            double dropRate = drops / (double) clicks;
            System.out.printf("sub-tick 310 ms, 0..%d ms jitter, 10 seeds: %.2f cells/s, %.3f %% drops%n",
                jitter, rate, dropRate * 100);
            assertTrue(rate >= 28, "walked rate: " + rate);
            assertTrue(dropRate < 0.01, "drop rate: " + dropRate);
        }
    }

    @Test
    void subTickKillSwitchPreservesEveryTickAndPacket() {
        Lane lane = lane(-60, -62, 57, (x, z) -> WHITE);
        Run old = run(lane, stock(WHITE), 0, 2, 7, 9);
        Run off = subTickRun(lane, false, 0, 1, 0, 0);
        assertEquals(old.ticks, off.ticks);
        assertEquals(old.sim.sentClicks, off.sim.sentClicks);
        assertEquals(old.sim.airPackets, off.sim.airPackets);
        assertTrue(off.sim.timedClicks.stream().allMatch(c -> c.sendMillis() % 50 == 0));
        System.out.printf("sub-tick off: existing fixed 9/7 %.2f cells/s; ms arrivals %.2f cells/s%n",
            old.perSecond(), off.perSecond());
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertTrue(!b.placeSubTickDispatch);
        assertEquals(310, b.placeClickWindowMillis);
    }

    @Test
    void subTickMixedColoursKeepMovesAndSelectionsFenced() {
        Random random = new Random(5);
        Lane lane = lane(-60, -62, 57, (x, z) -> COLOURS[random.nextInt(4)]);
        Run r = subTickRun(lane, true, 10, 5, 64, 0);
        assertTrue(r.sim.inventoryMoves > 0);
        assertTrue(r.sim.staleReports > 0);
        assertEquals(0, r.sim.moveRuleViolations);
        System.out.printf("sub-tick four colors, 64-stacks, 0..10 ms jitter: %.2f cells/s, %.3f %% drops%n",
            r.perSecond(), r.sim.paperDrops * 100.0 / r.sim.clicks);
    }

    @Test
    void nextTickWinsOverLateScheduledCallbackWithoutDuplicatingClicks() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        Run r = subTickRun(lane, true, 1, 1, 0, 100);
        assertEquals(lane.placements().size(), r.sim.clicks);
        assertTrue(r.sim.timedClicks.stream().allMatch(c -> c.sendMillis() % 50 == 0),
            "late callback is drained before the next tick's movement");
        assertEquals(0, r.sim.paperDrops);
    }

    private record Prepared(LaneSim sim, LaneRun<LaneSim.Shot> run, Lane lane, long tick) { }

    private static Prepared preparedRun() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        sim.millisecondArrivals = true;
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        var params = params(sim, 2, 7, 9);
        params = params.withPacing(params.pacing().withSubTickDispatch(true, 310));
        sim.begin(lane, NONE);
        LaneRun<LaneSim.Shot> run = new LaneRun<>(lane, sim, params);
        for (long t = 1; t < 100; t++) {
            sim.beginTick(t);
            assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.RUNNING, run.tick(t));
            sim.endTick();
            if (sim.hasScheduledClick()) return new Prepared(sim, run, lane, t);
            sim.advanceMillis((t + 1) * 50 - 1);
        }
        throw new AssertionError("no preparation");
    }

    @Test
    void preparedClicksCancelOnReleaseParkAndChangedClientState() {
        for (String action : List.of("release", "park", "container", "inventory", "input", "slot", "move", "latch", "switch")) {
            Prepared p = preparedRun();
            int sent = p.sim.clicks;
            switch (action) {
                case "release" -> p.run.close();
                case "park" -> p.run.requestPark();
                case "container" -> p.sim.containerOpen = true;
                case "inventory" -> p.sim.requestBusy = true;
                case "input" -> p.sim.dispatchReady = false;
                case "switch" -> p.sim.subTickEnabled = false;
                case "slot" -> p.sim.select(8);
                case "move" -> p.sim.teleport(30, ART_Y, 30);
                case "latch" -> { for (int i = 0; i < 3; i++) p.sim.pacer.sharedOutcome(true); }
            }
            p.sim.advanceMillis((p.tick + 1) * 50 - 1);
            assertEquals(sent, p.sim.clicks, action + " cancels the unsent reservation");
            assertTrue(!p.sim.hasScheduledClick());
            p.run.close();
            p.sim.advanceMillis((p.tick + 10) * 50);
            assertEquals(sent, p.sim.clicks, "no late callback after " + action);
        }
    }

    @Test
    void parkedPreparationReturnsToTodoAndPrintsEveryCellAfterResume() {
        Prepared p = preparedRun();
        p.run.requestPark();
        int sent = p.sim.clicks;
        long t = p.tick;
        while (!p.run.parked() && t < p.tick + 100) {
            p.sim.advanceMillis((t + 1) * 50 - 1);
            assertEquals(sent, p.sim.clicks);
            p.sim.beginTick(++t);
            p.run.tick(t);
            p.sim.endTick();
        }
        assertTrue(p.run.parked());
        p.run.resume();
        io.github.digitalisp.mapart.bot.TaskStatus status = io.github.digitalisp.mapart.bot.TaskStatus.RUNNING;
        for (; t < 5000 && status == io.github.digitalisp.mapart.bot.TaskStatus.RUNNING;) {
            p.sim.beginTick(++t);
            status = p.run.tick(t);
            p.sim.endTick();
            p.sim.advanceMillis((t + 1) * 50 - 1);
        }
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS, status, String.join("\n", p.sim.log));
        assertEquals(p.lane.placements().size(), p.sim.accepted);
        assertEquals(p.lane.placements().size(), p.sim.clicks, "no lost or duplicate preparation");
    }

    @Test
    void subTickRefusalsStillTripTheLatch() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        sim.millisecondArrivals = true;
        sim.anticheat = cell -> sim.nanoTime() % 50_000_000L != 0;
        var params = params(sim, 2, 7, 9);
        params = params.withPacing(params.pacing().withSubTickDispatch(true, 310));
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS, sim.runLane(lane, params, NONE, 20_000));
        assertTrue(!sim.pacer.fastPlacementOn());
        assertTrue(sim.anticheatCancels >= 3);
        assertEquals(lane.placements().size(), sim.accepted);
        assertEquals(0, sim.paperDrops);
    }

    private static void assertWireOrder(LaneSim sim) {
        int selected = 1, sequence = 0;
        boolean swapped = false, used = false;
        String previous = "";
        for (var packet : sim.wireEvents) {
            switch (packet.kind()) {
                case "select" -> {
                    assertTrue(!used && !swapped, "no slot change after use before movement");
                    selected = packet.slot();
                }
                case "inventory" -> assertTrue(!used && !swapped, "no inventory move after use before movement");
                case "swap" -> swapped = !swapped;
                case "use" -> {
                    assertTrue(swapped);
                    assertEquals(selected, packet.slot());
                    assertEquals(++sequence, packet.sequence());
                    used = true;
                }
                case "swing" -> assertEquals("use", previous);
                case "move" -> {
                    assertTrue(!swapped, "batch closes before movement");
                    used = false;
                }
            }
            previous = packet.kind();
        }
        assertTrue(!swapped);
    }

    @Test
    void earlyTickRecomputesPreparationAndCancelsTheOldCallback() {
        Prepared p = preparedRun();
        int sent = p.sim.clicks;
        long deadline = p.sim.pacer.wallWindow().opensAt(310) / 1_000_000L;
        p.sim.beginTickAt(p.tick + 1, deadline - 1);
        p.run.tick(p.tick + 1);
        p.sim.endTick();
        assertEquals(sent, p.sim.clicks, "early tick cannot bypass the wall window");
        p.sim.advanceMillis(deadline);
        assertTrue(p.sim.clicks > sent);
        int after = p.sim.clicks;
        p.sim.advanceMillis(deadline + 1);
        assertEquals(after, p.sim.clicks, "old callback cannot send twice");
        assertWireOrder(p.sim);
        p.run.close();
    }

    @Test
    void deferredUseRemainsAccountedWhenSwingFailsAndLaneStops() {
        Prepared p = preparedRun();
        int before = p.sim.clicks;
        p.sim.failSwing = true;
        p.sim.advanceMillis((p.tick + 1) * 50 - 1);
        assertEquals(before + 1, p.sim.clicks);
        assertTrue(p.run.failure().contains("swing failed"));
        p.sim.failSwing = false;
        p.sim.beginTick(p.tick + 1);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.FAILED, p.run.tick(p.tick + 1));
        p.sim.advanceMillis((p.tick + 10) * 50);
        assertEquals(before + 1, p.sim.clicks);
        assertWireOrder(p.sim);
    }

    @Test
    void subTickBatchKillSwitchKeepsOneSwapPairPerUse() {
        Lane lane = lane(-60, -62, -40, (x, z) -> WHITE);
        LaneSim sim = sim(stock(WHITE));
        sim.millisecondArrivals = true;
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.pacedLaneWalk = true;
        b.placeClicksPerTick = 2;
        b.placeSubTickDispatch = true;
        b.placeAirPlaceBatch = false;
        sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
        assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS,
            sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000));
        assertEquals(lane.placements().size(), sim.accepted);
        assertEquals(sim.clicks * 4, sim.airPackets.size());
        assertWireOrder(sim);
    }

    @Test
    void subTickNeverDefersFullBlocksOrLookClicks() {
        for (boolean look : List.of(false, true)) {
            String item = look ? WHITE : WOOL;
            List<Placement> ps = new ArrayList<>();
            for (int z = -62; z <= -40; z++) for (int x = -62; x <= -58; x++)
                ps.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(item), item));
            Lane lane = new Lane(0, new Pos(-60, ART_Y, -63), new Pos(-60, ART_Y, -39), ps);
            LaneSim sim = sim(stock(item));
            sim.millisecondArrivals = true;
            MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
            b.placeSubTickDispatch = true;
            b.placeClicksPerTick = 3;
            b.placeMode = look ? "LOOK" : "AIRPLACE";
            sim.silent = PlaceMode.silentRotation(b);
            sim.teleport(lane.from().x() + 0.5, ART_Y, lane.from().z() + 0.5);
            assertEquals(io.github.digitalisp.mapart.bot.TaskStatus.SUCCESS,
                sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 20_000), String.join("\n", sim.log));
            assertEquals(lane.placements().size(), sim.accepted);
            assertEquals(1, sim.maxPlacementsPerTick);
            assertTrue(sim.timedClicks.stream().allMatch(c -> c.sendMillis() % 50 == 0));
        }
    }

}
