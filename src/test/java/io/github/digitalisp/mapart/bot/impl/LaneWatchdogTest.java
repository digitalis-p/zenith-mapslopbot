package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
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
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lane hang of 2026-10-15 (live): after "Lane 6: 1152 placed" the bot stood still in BUILD for 11 minutes
 * with no log line, through a pause and a stop (each started the next lane afresh); only a reconnect ended it. Two parts:
 * hotbar slots left unknown by a lookahead move with no answer ({@link InventoryEchoes}) now ask for the full inventory
 * and are trusted again after a bound ({@code build.unknownSlotResync}, {@code build.unknownSlotTrustTicks}), and a lane
 * that makes no progress ends with one line saying why ({@code build.laneWatchdogTicks}, {@link LaneRun#watchdog}).
 */
class LaneWatchdogTest {
    private static final String CANDLE = "minecraft:white_candle";
    private static final String PLATE = "minecraft:stone_pressure_plate";
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement p) { }
        @Override public void skipped(Placement p, String reason) { }
    };

    private static LaneSim sim(Map<Pos, BlockSpec> world, int candles) {
        Map<String, Integer> stock = new HashMap<>();
        stock.put(CANDLE, candles);
        stock.put(PLATE, 10_000);
        LaneSim sim = new LaneSim(new LaneSim.Blocks() {
            @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
            @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
        }, new LaneSim.Items() {
            @Override public int count(String item) { return stock.getOrDefault(item, 0); }
            @Override public boolean take(String item) {
                int n = count(item);
                if (n == 0) return false;
                stock.put(item, n - 1);
                return true;
            }
        }, -64, -64, 64, new AdaptivePacer(1, 1, 1), 5);
        sim.confirmDelay = 3;
        sim.echoes = true;
        // two stacks of each item in the hotbar at the lane start: the candles (the lane's first cell) in slots 1 and 2,
        // with 128 candles carried none in the main inventory (live: hotbar 1 x26 and 4 x1, none in the main inventory)
        sim.stackSize = 64;
        sim.hotbarStacksPerItem = 2;
        sim.moveRequestTicks = 2;
        sim.moveFenceTicks = 7;
        sim.teleport(-59.5, 64, -63.5);
        return sim;
    }

    /** 9 x 40 cells, a third of them candles, the first one too (so the candles' stack is in slot 1). */
    private static Lane lane(int index) {
        List<Placement> ps = new ArrayList<>();
        Random rnd = new Random(3);
        for (int z = -62; z <= -23; z++) {
            for (int x = -64; x <= -56; x++) {
                String id = ps.isEmpty() || rnd.nextInt(3) == 0 ? CANDLE : PLATE;
                ps.add(new Placement(new Pos(x, 64, z), BlockSpec.of(id), id));
            }
        }
        return new Lane(index, new Pos(-60, 64, -63), new Pos(-60, 64, -22), ps);
    }

    private static int candleCells(Lane lane) {
        int n = 0;
        for (Placement p : lane.placements()) if (CANDLE.equals(p.itemId())) n++;
        return n;
    }

    /** The live build settings of that day (lookahead with eviction, stack switch, fence clicks; one click a tick). */
    private static LaneRun.Params params(LaneSim sim) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeHotbarLookahead = true;
        b.placeHotbarLookaheadEvict = true;
        sim.silent = PlaceMode.silentRotation(b);
        return LaneRun.Params.of(b, sim.tileArea());
    }

    /** Slot 1 unknown: a lookahead move into it got no answer and an inventory task's hold released it (silently). */
    private static InventoryEchoes unknownSlot1() {
        return unknown(1);
    }

    /** {@code slots} unknown, each as {@link #unknownSlot1}. */
    private static InventoryEchoes unknown(int... slots) {
        InventoryEchoes echoes = new InventoryEchoes();
        MapArtConfig.BuildCfg defaults = new MapArtConfig.BuildCfg();
        echoes.configure(defaults.unknownSlotResync, defaults.unknownSlotTrustTicks); // as the bot tick sets them
        for (int slot : slots) {
            echoes.quarantine(slot, CANDLE, echoes.mark());
            echoes.inventoryNeeded();
            assertFalse(echoes.known(slot));
        }
        assertEquals(-1, echoes.quarantinedSlot());
        assertTrue(echoes.anyUnknown());
        return echoes;
    }

    private static String log(LaneSim sim) {
        return String.join("\n", sim.log);
    }

    private static long count(LaneSim sim, String part) {
        return sim.log.stream().filter(l -> l.contains(part)).count();
    }

    /** Hotbar.fenced's rule for the request in a run of the sim: the lane sends it, on a still tick, as the bot ticks. */
    private record Resyncs(List<Long> ticks, List<String> events) { }

    private static BiPredicate<Long, LaneRun<LaneSim.Shot>> session(LaneSim sim, InventoryEchoes echoes, Resyncs out,
                                                                    int answerTicks) {
        long[] answerAt = {Long.MAX_VALUE};
        return (t, run) -> {
            echoes.tick(true, false);
            String e = echoes.takeEvent();
            if (e != null) out.events().add(e);
            if (echoes.needsResync() && Hotbar.resyncAllowed(false, false, false, sim.stillLastTick(), Long.MAX_VALUE)) {
                echoes.resyncSent();
                out.ticks().add(t);
                if (answerTicks >= 0) answerAt[0] = t + answerTicks;
            }
            if (t == answerAt[0]) {
                String[] full = new String[9];
                full[1] = CANDLE; // the server's answer: the whole hotbar, the candles where the cache showed them
                full[2] = CANDLE;
                echoes.fullReport(full);
            }
            return true;
        };
    }

    // ------------------------------------------------------------------------------------------------- unknown slots

    @Test
    void beforeTheFixCellsWhoseItemWasOnlyInUnknownSlotsWereSkippedAsMissing() {
        // PlaceLaneTask's view of an unknown slot (ZenithIo: counts, slots and the held item leave it out): the run
        // neither waited nor asked, it skipped every candle cell as "missing item" with 128 candles in the hotbar
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 128);
        sim.hideUnknown = true;
        sim.unknownResync = false; // build.unknownSlotResync false: the old behaviour
        InventoryEchoes echoes = unknown(1, 2);
        sim.knownSlot = echoes::known;
        Lane lane = lane(7);
        TaskStatus s = sim.runLane(lane, params(sim), NONE, 6000);
        assertEquals(TaskStatus.SUCCESS, s, log(sim));
        assertEquals(candleCells(lane), sim.skipReasons.getOrDefault("missing item minecraft:white_candle", 0), log(sim));
        assertFalse(echoes.known(1) || echoes.known(2), "nothing asked the server: the slots stay unknown for good");
    }

    @Test
    void unknownSlotsAskForTheFullInventoryOnAStillTickAndTheLanePlacesEverything() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 128);
        sim.hideUnknown = true;
        InventoryEchoes echoes = unknown(1, 2);
        sim.knownSlot = echoes::known;
        Resyncs r = new Resyncs(new ArrayList<>(), new ArrayList<>());
        List<Boolean> still = new ArrayList<>();
        Lane lane = lane(7);
        BiPredicate<Long, LaneRun<LaneSim.Shot>> session = session(sim, echoes, r, 5);
        TaskStatus s = sim.runLane(lane, params(sim), NONE, 6000, (t, run) -> {
            int before = r.ticks().size();
            boolean ok = session.test(t, run);
            if (r.ticks().size() > before) still.add(sim.stillLastTick());
            return ok;
        });
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(lane.placements().size(), sim.accepted, log);
        assertTrue(sim.skipReasons.isEmpty(), sim.skipReasons + "\n" + log);
        assertEquals(1, r.ticks().size(), "one request, answered: " + r.ticks());
        assertEquals(List.of(true), still, "the request goes out on a still tick only");
        assertTrue(echoes.known(1) && echoes.known(2));
        assertTrue(r.events().isEmpty(), "answered: the cache is never trusted blindly: " + r.events());
        assertEquals(0, sim.wrongClicks, log);
    }

    @Test
    void withoutAnAnswerTheRequestRepeatsAtMostOncePer40TicksAndTheCacheIsTrustedAfterTheBound() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 128);
        sim.hideUnknown = true;
        InventoryEchoes echoes = unknown(1, 2);
        sim.knownSlot = echoes::known;
        sim.watchdogTicks = 200; // the hold for the trust bound (100 ticks) is no stall
        Resyncs r = new Resyncs(new ArrayList<>(), new ArrayList<>());
        Lane lane = lane(7);
        TaskStatus s = sim.runLane(lane, params(sim), NONE, 6000, session(sim, echoes, r, -1));
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(lane.placements().size(), sim.accepted, log);
        assertTrue(sim.skipReasons.isEmpty(), sim.skipReasons + "\n" + log);
        assertFalse(r.ticks().isEmpty());
        for (int i = 1; i < r.ticks().size(); i++) {
            assertTrue(r.ticks().get(i) - r.ticks().get(i - 1) >= InventoryEchoes.UNKNOWN_RESYNC_INTERVAL, "" + r.ticks());
        }
        assertTrue(r.ticks().size() <= 3, "100 ticks of trust bound, 40 between requests: " + r.ticks());
        assertEquals(1, r.events().size(), "the trust is logged once: " + r.events());
        assertTrue(r.events().getFirst().contains("trusting the inventory cache"), r.events().getFirst());
        assertTrue(echoes.known(1) && echoes.known(2));
        assertEquals(1, count(sim, "holding for minecraft:white_candle: no usable stack"), log);
        System.out.println(sim.log.stream().filter(l -> l.contains("holding for")).findFirst().orElseThrow());
        System.out.println(r.events().getFirst() + "; requests at ticks " + r.ticks());
        assertTrue(log.contains("slot 1 x64 unknown, slot 2 x64 unknown"), log);
        assertEquals(0, count(sim, "watchdog"), log);
    }

    @Test
    void unknownSlotRequestsAreBoundedAndRateLimited() {
        InventoryEchoes echoes = unknownSlot1();
        assertTrue(echoes.needsResync(), "no quarantine left: the unknown slot asks at once");
        assertEquals(1, echoes.resyncSlot());
        echoes.resyncSent();
        assertEquals(1, echoes.unknownResyncs());
        for (int t = 1; t < InventoryEchoes.UNKNOWN_RESYNC_INTERVAL; t++) {
            echoes.tick(true, false);
            assertFalse(echoes.needsResync(), "at most one request per window, tick " + t);
        }
        echoes.tick(true, false);
        assertTrue(echoes.needsResync());
        // a recovery has the request to itself; the unknown slot waits
        echoes.quarantine(4, CANDLE, echoes.mark());
        assertFalse(echoes.needsResync());
        echoes.report(4, CANDLE);
        assertTrue(echoes.needsResync());
        // a report of the slot ends it; a full answer names every slot
        echoes.report(1, CANDLE);
        assertTrue(echoes.known(1));
        assertFalse(echoes.anyUnknown());
        assertFalse(echoes.needsResync());
        InventoryEchoes full = unknownSlot1();
        full.fullReport(new String[9]);
        assertTrue(full.known(1));
        assertFalse(full.needsResync());
        // the switch off: the old wait for a report, no request, no trust
        InventoryEchoes off = unknownSlot1();
        off.configure(false, 0);
        for (int t = 0; t < 1000; t++) off.tick(true, false);
        assertFalse(off.needsResync());
        assertFalse(off.known(1));
        assertNull(off.takeEvent());
        // the trust bound: once, after exactly unknownSlotTrustTicks
        InventoryEchoes trust = unknownSlot1();
        trust.configure(true, 100);
        for (int t = 1; t < 100; t++) {
            trust.tick(true, false);
            assertNull(trust.takeEvent());
        }
        assertFalse(trust.known(1));
        trust.tick(true, false);
        assertTrue(trust.known(1));
        assertNotNull(trust.takeEvent());
        for (int t = 0; t < 300; t++) trust.tick(true, false);
        assertNull(trust.takeEvent(), "logged once");
        // before the first bot tick sets the switches from the config: the old wait
        InventoryEchoes fresh = new InventoryEchoes();
        fresh.quarantine(1, CANDLE, fresh.mark());
        fresh.inventoryNeeded();
        for (int t = 0; t < 500; t++) fresh.tick(true, false);
        assertFalse(fresh.needsResync());
        assertFalse(fresh.known(1));
        // a recovery whose request got no answer: its request counts as the first, the next one a window later
        InventoryEchoes recovered = new InventoryEchoes();
        recovered.configure(true, 100);
        recovered.quarantine(3, CANDLE, recovered.mark());
        for (int t = 0; t < MoveFence.TIMEOUT; t++) recovered.tick(true, false);
        assertTrue(recovered.needsResync());
        recovered.resyncSent();
        for (int t = 0; t < InventoryEchoes.RESYNC_TIMEOUT; t++) recovered.tick(true, false);
        assertEquals(-1, recovered.quarantinedSlot());
        assertFalse(recovered.known(3));
        for (int t = 1; t < InventoryEchoes.UNKNOWN_RESYNC_INTERVAL; t++) {
            assertFalse(recovered.needsResync(), "no second request right behind the recovery's, tick " + t);
            recovered.tick(true, false);
        }
        recovered.tick(true, false);
        assertTrue(recovered.needsResync());
        // a new connection forgets it all
        InventoryEchoes reset = unknownSlot1();
        reset.reset();
        assertFalse(reset.anyUnknown());
        assertFalse(reset.needsResync());
    }

    @Test
    void theRequestIsAnInventoryClickOnlyOnAStillTickWithNothingInFlight() {
        long ok = 50_000_000L;
        assertTrue(Hotbar.resyncAllowed(false, false, false, true, ok));
        assertFalse(Hotbar.resyncAllowed(false, false, false, false, ok), "never while the last input moved (MultiActionsC)");
        assertFalse(Hotbar.resyncAllowed(true, false, false, true, ok), "never with a request of ours in flight");
        assertFalse(Hotbar.resyncAllowed(false, true, false, true, ok), "never with a container open");
        assertFalse(Hotbar.resyncAllowed(false, false, true, true, ok), "never with anyone's inventory request");
        assertFalse(Hotbar.resyncAllowed(false, false, false, true, ok - 1), "never within 50 ms of a placement");
    }

    // ------------------------------------------------------------------------------------------------------ watchdog

    @Test
    void aLaneWhoseInputIsNeverOursStandsStillForeverWithoutALogLine() {
        // the incident's shape: from its first tick the lane can neither click nor make its hotbar layout's move (both
        // wait for an executed still tick), and nothing bounds that wait
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 64);
        sim.hotbarStacksPerItem = 1; // the layout wants a second stack of the plates: one move before the walk
        sim.inputLost = true;
        TaskStatus s = sim.runLane(lane(7), params(sim), NONE, 6000);
        assertEquals(TaskStatus.FAILED, s, log(sim));
        assertEquals("TIMEOUT after 6000 ticks (0 placed, 0 skipped of 360, phase APPROACH)", sim.log.getLast());
        assertEquals(1, sim.log.size(), "no line of its own in 6000 ticks: " + log(sim));
        assertEquals(0, sim.clicks);
    }

    @Test
    void theWatchdogEndsALaneThatMakesNoProgressAndSaysWhy() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 64);
        sim.hotbarStacksPerItem = 1;
        sim.inputLost = true;
        sim.watchdogTicks = 200;
        TaskStatus s = sim.runLane(lane(7), params(sim), NONE, 6000);
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertTrue(sim.lastTicks <= 202, "ended within the bound: " + sim.lastTicks);
        assertEquals(1, count(sim, "watchdog"), log);
        String line = sim.log.stream().filter(l -> l.contains("watchdog")).findFirst().orElseThrow();
        System.out.println(line);
        assertTrue(line.startsWith("Lane 7: watchdog: no placement, click, hotbar change or movement for 200 ticks"), line);
        assertTrue(line.contains("hotbar layout"), line);
        assertTrue(line.contains("input lost"), line);
        assertTrue(line.contains("360 cells left for the next round"), line);
        assertTrue(sim.skipReasons.isEmpty(), "nothing skipped: the cells stay for the next round " + sim.skipReasons);
    }

    /**
     * Live 2026-10-21 00:55-01:35 (ABD [9,5]): lane 11's pre-lane layout swapped two stacks back and forth (the cycle in
     * {@code LaneHotbarTest}); each swap changed the hotbar, so the watchdog saw progress and the lane stood in APPROACH
     * without a line until the task timeout, four times.
     */
    @Test
    void aLayoutThatNeverSettlesKeptTheWatchdogFromTripping() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 64);
        sim.watchdogTicks = 200;
        sim.layoutCycle = true; // build.laneWatchdogHotbarTicks 0: as before
        TaskStatus s = sim.runLane(lane(7), params(sim), NONE, 6000);
        assertEquals(TaskStatus.FAILED, s, log(sim));
        assertEquals("TIMEOUT after 6000 ticks (0 placed, 0 skipped of 360, phase APPROACH)", sim.log.getLast());
        assertEquals(0, count(sim, "watchdog"), log(sim));
        assertTrue(sim.layoutMoves > 100, "the layout kept swapping: " + sim.layoutMoves);
    }

    @Test
    void hotbarChangesAloneCountAsProgressForAtMostTheBound() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 64);
        sim.watchdogTicks = 200;
        sim.watchdogHotbarTicks = 1200;
        sim.layoutCycle = true;
        TaskStatus s = sim.runLane(lane(7), params(sim), NONE, 6000);
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertTrue(sim.lastTicks >= 1200 && sim.lastTicks <= 1215, "ended at the bound: " + sim.lastTicks);
        assertEquals(1, count(sim, "watchdog"), log);
        String line = sim.log.stream().filter(l -> l.contains("watchdog")).findFirst().orElseThrow();
        System.out.println(line);
        assertTrue(line.startsWith("Lane 7: watchdog: no placement, click or movement for 12"), line);
        assertTrue(line.contains("only hotbar changes (phase APPROACH, hotbar layout"), line);
        assertTrue(line.contains("360 cells left for the next round"), line);
        assertTrue(sim.skipReasons.isEmpty(), "nothing skipped: " + sim.skipReasons);
    }

    @Test
    void theHotbarBoundLeavesLanesThatPlaceAlone() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 10_000);
        sim.watchdogTicks = 200;
        sim.watchdogHotbarTicks = 40; // far below a lane's length: placements and movement keep resetting it
        Lane lane = lane(7);
        TaskStatus s = sim.runLane(lane, params(sim), NONE, 6000);
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(lane.placements().size(), sim.accepted, log);
        assertEquals(0, count(sim, "watchdog"), log);
    }

    @Test
    void theWatchdogLeavesLanesThatMakeProgressAlone() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(world, 10_000);
        sim.watchdogTicks = 200;
        Lane lane = lane(7);
        // parked for 400 world ticks mid-lane (the engine eats): a park has its own bound, the watchdog waits
        TaskStatus s = sim.runLane(lane, params(sim), NONE, 6000, (t, run) -> {
            if (t == 150) run.requestPark();
            if (t > 150 && t < 550 && run.parked()) return false;
            if (t == 550) run.resume();
            return true;
        });
        String log = log(sim);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(lane.placements().size(), sim.accepted, log);
        assertEquals(0, count(sim, "watchdog"), log);
    }
}
