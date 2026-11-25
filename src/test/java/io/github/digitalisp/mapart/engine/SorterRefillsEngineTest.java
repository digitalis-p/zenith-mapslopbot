package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Platforms whose collection system sorts every item back ({@code PlatformCfg.sorterRefills}; the owner 2026-10-14: a
 * near shortage on the soul sand platform is the sorter still working). The
 * near sorter chest holds 51 of the 60 lime cells of the tile's first row and the far sorter has plenty: before, the
 * load went to far storage (60-80 s live); now those cells wait, the rest is built, and the bot waits by the sorter.
 */
class SorterRefillsEngineTest {
    private static final int LONG = 160_000;
    private static final Pos FAR_CHEST = new Pos(-90, 61, 185);
    private static final Pos NEAR_LIME = new Pos(-90, 61, 104);

    private static Sim sim(boolean refills, RecordingLogger log, int nearLime) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.farStorage = true;
        sim.moss.sorterRefills = refills;
        MapArtConfig.RegionCfg far = new MapArtConfig.RegionCfg();
        far.min = new Pos(-100, 60, 180);
        far.max = new Pos(-80, 62, 190);
        far.name = "far sorter";
        sim.config.site.storage.materialRegions.add(far);
        sim.bot.addContainer(FAR_CHEST, Map.of(LIME, 1728));
        sim.bot.addContainer(NEAR_LIME, nearLime > 0 ? Map.of(LIME, nearLime) : Map.of());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** White/red stripes over {@code rows} rows, lime in the first {@code lime} cells of row 0. */
    private static TileArt fewLime(int rows, int lime) {
        TileArt a = new TileArt();
        String[] cols = {WHITE, RED, Sim.TERRACOTTA};
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(pz == 0 && px < lime ? LIME : cols[px % 3]));
        return a;
    }

    private static int farTrips(Sim sim) {
        return (int) sim.bot.history.stream().filter(h -> h.startsWith("withdraw " + FAR_CHEST)).count();
    }

    private static boolean said(RecordingLogger log, String part) {
        return log.at(Level.INFO).stream().anyMatch(l -> l.contains(part)) || log.at(Level.WARN).stream().anyMatch(l -> l.contains(part));
    }

    @Test
    void aShortNearItemWaitsForTheSorterInsteadOfAFarTrip() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 51);
        Job job = sim.addJob("j0960", "Sorter", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        boolean[] returned = {false};
        assertTrue(sim.runUntil(() -> {
            // the sorter returns lime once the bot waits for it (the rest of the tile is built by then)
            if (!returned[0] && said(log, "waiting by the sorter chests")) {
                sim.bot.containers.get(NEAR_LIME).items.merge(LIME, 200, Integer::sum);
                returned[0] = true;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + log.lines);
        assertTrue(returned[0], "the bot waited for the sorter: " + log.lines);
        assertEquals(0, farTrips(sim), "no far trip");
        assertFalse(said(log, "re-scanned (every near container)"), "no full near sweep for sorter lag: " + log.lines);
        // lime never went into this platform's sorter (no nuke of it, no drop): plain wording
        assertTrue(said(log, "none of it went into this platform's sorter: it needs restocking"), log.lines.toString());
        assertFalse(said(log, "the sorter is still returning them"), log.lines.toString());
        assertTrue(said(log, "the sorter returned what was missing"), log.lines.toString());
        assertEquals(1, sim.output().maps.size(), "verified and stored");
    }

    /** Cells that only wait for the sorter use up no VERIFY repair pass (live 2026-10-14 00:58: BLOCKED on 33 cells). */
    @Test
    void cellsWaitingForTheSorterUseUpNoRepairPass() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 51);
        sim.config.build.maxRepairPasses = 0;
        Job job = sim.addJob("j0962", "Sorter", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        boolean[] returned = {false};
        assertTrue(sim.runUntil(() -> {
            if (!returned[0] && said(log, "waiting by the sorter chests")) {
                sim.bot.containers.get(NEAR_LIME).items.merge(LIME, 200, Integer::sum);
                returned[0] = true;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + log.lines);
        assertTrue(log.at(Level.ERROR).stream().noneMatch(l -> l.contains("BLOCKED")), log.lines.toString());
        assertEquals(0, farTrips(sim), "no far trip");
    }

    /** The AUTO planner (whole stacks, top-ups) as live: no far container for a lagging item, not even its last few. */
    @Test
    void theAutoPlannerTakesNothingFromFarForALaggingItem() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 51);
        sim.newEngine(new SerpentinePlanner(), new ConfiguredRestockPlanner(() -> sim.config.build.restockPlanner), log);
        Job job = sim.addJob("j0961", "Sorter", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        boolean[] returned = {false};
        assertTrue(sim.runUntil(() -> {
            if (!returned[0] && said(log, "waiting by the sorter chests")) {
                sim.bot.containers.get(NEAR_LIME).items.merge(LIME, 200, Integer::sum);
                returned[0] = true;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + log.lines);
        assertEquals(0, farTrips(sim), "no far trip: " + log.lines);
    }

    @Test
    void theLanesThatCanBeBuiltGoFirst() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 51);
        Job job = sim.addJob("j0961", "First", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        int[] otherAtWait = {-1};
        assertTrue(sim.runUntil(() -> {
            if (otherAtWait[0] < 0 && said(log, "waiting by the sorter chests")) {
                int n = 0;
                for (int pz = 0; pz < 20; pz++)
                    for (int px = 0; px < 128; px++)
                        if (!sim.world.block(new Pos(sim.moss.minX + px, sim.moss.artY, sim.moss.minZ + pz)).isAir()) n++;
                otherAtWait[0] = n;
                sim.bot.containers.get(NEAR_LIME).items.merge(LIME, 200, Integer::sum);
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        // 20 rows of 128 cells, 9 of the lime cells wait: everything else was placed before the wait
        assertEquals(20 * 128 - 9, otherAtWait[0], "the tile but the 9 lime cells was built when the wait began");
    }

    @Test
    void theSwitchOffKeepsTheFarTrip() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(false, log, 51);
        Job job = sim.addJob("j0962", "Off", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, farTrips(sim), "the old behavior: a far trip for the missing lime");
        assertTrue(!said(log, "sorter"), log.lines.toString());
    }

    @Test
    void whenTheNearCountDoesNotRiseItFallsBackToFarStorage() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 51);
        sim.config.build.sorterRefillMinutes = 0.2; // 12 s
        sim.config.build.sorterRefillPollSeconds = 3;
        Job job = sim.addJob("j0963", "Timeout", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(said(log, "has not risen for"), log.lines.toString());
        assertEquals(1, farTrips(sim), "then the far trip");
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void anItemNoNearChestEverHeldComesFromFarStorageAtOnce() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, 0); // a new colour: the near lime chest never held any
        Job job = sim.addJob("j0964", "New", 1, 1, List.of("moss"), (x, z) -> fewLime(20, 60));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, farTrips(sim), "not reclaimable: far storage as before");
        assertTrue(!said(log, "waiting by the sorter chests"), log.lines.toString());
    }

    /** The in-order map is short of lime by the stock index, but the near chests hold (and held) lime: not skipped. */
    @Test
    void selectDoesNotSkipAMapForSorterLag() {
        for (boolean refills : new boolean[] {false, true}) {
            RecordingLogger log = new RecordingLogger();
            Sim sim = new Sim();
            sim.modules.state.put("AntiAFK", false);
            sim.config.build.waitingRecheckMinutes = 2;
            sim.moss.sorterRefills = refills;
            sim.bot.addContainer(NEAR_LIME, Map.of(LIME, 100));
            sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
            // map [1,0] needs 192 lime (the near chest has 100); maps [0,0] and [2,0] are fully stocked
            Job job = sim.addJob("j097" + (refills ? 1 : 0), "Lag", 3, 1, List.of("moss"),
                (x, z) -> x == 1 ? PartialBuildTest.mixed(6) : x == 0 ? Sim.stripes(4, 0) : Sim.single(RED, 3));
            sim.start();
            boolean[] returned = {false};
            assertTrue(sim.runUntil(() -> {
                if (!returned[0] && sim.engine.status().note().startsWith("waiting")) {
                    sim.bot.containers.get(NEAR_LIME).items.put(LIME, 1728);
                    returned[0] = true;
                }
                if (!returned[0] && refills && said(log, "waiting by the sorter chests")) {
                    sim.bot.containers.get(NEAR_LIME).items.put(LIME, 1728);
                    returned[0] = true;
                }
                return job.status == JobStatus.DONE;
            }, LONG), sim.state() + log.lines);
            List<String> order = sim.output().maps.stream().map(FakeBot.MapItem::name).toList();
            if (refills) {
                assertEquals(List.of("Lag 0,0", "Lag 1,0", "Lag 2,0"), order, "in queue order: no map skipped");
                assertTrue(!said(log, "is fully stocked: building it before"), log.lines.toString());
                assertTrue(!said(log, "no queued map is fully stocked"), log.lines.toString());
            } else {
                assertEquals(List.of("Lag 0,0", "Lag 2,0", "Lag 1,0"), order, "the old behavior: map [1,0] skipped");
            }
        }
    }

    // ---------------------------------------------------------------------------------------- the pickup spot

    private static final Pos PICKUP = new Pos(-100, 61, 96);

    /** 6 rows of lime: 768 cells; the near chest holds 100 (a gap above sorterRefillMaxCells), far storage none. */
    private static TileArt limeRows() {
        TileArt a = new TileArt();
        for (int pz = 0; pz < 6; pz++) for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(LIME));
        return a;
    }

    private static Sim pickupSim(RecordingLogger log, boolean spot) {
        Sim sim = sim(true, log, 100);
        sim.bot.containers.remove(FAR_CHEST); // no lime in far storage
        sim.world.set(FAR_CHEST, BlockSpec.AIR);
        if (spot) sim.moss.sorterPickup = PICKUP;
        return sim;
    }

    @Test
    void theTrickleIsPickedUpAtTheSpotThenOneFullLoad() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.bot.pickupTrickle.put(LIME, 200); // per second of standing
        Job job = sim.addJob("j0980", "Pick", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(List.of(PICKUP), sim.bot.pickupStands, "one stand at the spot");
        assertEquals(List.of("covered"), sim.bot.pickupReasons);
        long restocks = log.at(Level.INFO).stream().filter(l -> l.startsWith("restock: ") && l.contains("withdraw")).count();
        assertTrue(restocks <= 3, restocks + " restocks: " + log.lines);
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void theStandEndsWhenNothingMoreComes() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.config.build.sorterPickupStallSeconds = 2;
        Job job = sim.addJob("j0981", "Stall", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.pickupReasons.isEmpty(), LONG), sim.state() + log.lines);
        assertEquals("stalled", sim.bot.pickupReasons.getFirst());
        assertEquals(1, sim.bot.pickupStands.size());
    }

    @Test
    void theStandEndsAtTheMaxTimeWhileItStillTrickles() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.bot.pickupTrickle.put(LIME, 1);
        sim.config.build.sorterPickupMaxSeconds = 6;
        Job job = sim.addJob("j0982", "Max", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.pickupReasons.isEmpty(), LONG), sim.state() + log.lines);
        assertEquals("time", sim.bot.pickupReasons.getFirst());
    }

    @Test
    void theStandEndsWhenTheInventoryIsFull() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.bot.pickupTrickle.put(LIME, 200);
        sim.config.build.sorterPickupMinFreeSlots = 40; // more than the inventory has: full at once
        Job job = sim.addJob("j0983", "Slots", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.pickupReasons.isEmpty(), LONG), sim.state() + log.lines);
        assertEquals("slots", sim.bot.pickupReasons.getFirst());
    }

    /**
     * A gap above {@code sorterRefillMaxCells} with none of it in far storage waits for the sorter too (live 2026-10-15,
     * Galileo [0,1]: yellow terracotta in the sorter after the nukes; four rounds without a load, then BLOCKED).
     */
    @Test
    void aBigGapWithNothingInFarStorageWaitsForTheSorterNotBlocked() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, false); // 768 lime needed, 100 near, none far, no pickup spot
        Job job = sim.addJob("j0985", "Big", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        boolean[] returned = {false};
        assertTrue(sim.runUntil(() -> {
            if (!returned[0] && said(log, "waiting by the sorter chests")) {
                sim.bot.containers.get(NEAR_LIME).items.merge(LIME, 1000, Integer::sum);
                returned[0] = true;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + log.lines);
        assertTrue(returned[0], "it waited by the sorter: " + log.lines);
        assertTrue(log.at(Level.ERROR).stream().noneMatch(l -> l.contains("BLOCKED")), log.lines.toString());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void withoutASpotThereIsNoStand() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, false);
        sim.bot.pickupTrickle.put(LIME, 200);
        Job job = sim.addJob("j0984", "Off", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        sim.runUntil(() -> false, 6_000);
        assertTrue(sim.bot.pickupStands.isEmpty(), "no pickup spot: no stand");
        assertTrue(!said(log, "pickup spot"), log.lines.toString());
    }

    private static final Pos PRIME = new Pos(-60, 61, 90);

    @Test
    void theStandPrimesAtTheNorthCentreThenWaitsAtTheEdge() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.sorterPickupPrime = PRIME;
        sim.bot.pickupTrickle.put(LIME, 200);
        Job job = sim.addJob("j0985", "Prime", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(List.of(PRIME), sim.bot.pickupPrimes, "the prime cell first");
        assertEquals(List.of(PICKUP), sim.bot.pickupStands, "then the edge");
        assertEquals(List.of("covered"), sim.bot.pickupReasons);
    }

    @Test
    void theStallTimerRunsAtTheEdgeAfterThePrimeCell() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.sorterPickupPrime = PRIME;
        sim.config.build.sorterPickupStallSeconds = 3; // 60 ticks
        Job job = sim.addJob("j0986", "PrimeStall", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        long[] began = {-1};
        assertTrue(sim.runUntil(() -> {
            if (began[0] < 0 && !sim.bot.pickupPrimes.isEmpty()) began[0] = sim.ticks;
            return !sim.bot.pickupReasons.isEmpty();
        }, LONG), sim.state() + log.lines);
        assertEquals("stalled", sim.bot.pickupReasons.getFirst());
        long took = sim.ticks - began[0];
        assertTrue(took >= 60, "the stall time at the edge: " + took + " ticks");
    }

    @Test
    void withoutAPrimeCellTheStandGoesStraightToTheEdge() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.bot.pickupTrickle.put(LIME, 200);
        Job job = sim.addJob("j0987", "NoPrime", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.pickupPrimes.isEmpty());
    }

    private static final Pos JUNK_STAND = new Pos(-98, 61, 96);
    private static final Pos JUNK_AT = new Pos(-94, 61, 96);

    @Test
    void whatNoLoadNeedsGoesBackAtTheEdgeAndJunkToTheOceanSpot() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.junkDropStand = JUNK_STAND;
        sim.moss.junkDropAt = JUNK_AT;
        sim.bot.pickupTrickle.put(LIME, 200);
        sim.bot.pickupTrickle.put(RED, 20); // a map material of the queue nobody needs now
        sim.bot.pickupTrickle.put("minecraft:rotten_flesh", 5);
        sim.bot.pickupTrickle.put("minecraft:ominous_bottle", 2); // an ominous bottle is junk too
        Job job = sim.addJob("j0990", "Back", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(sim.bot.droppedItems.getOrDefault(RED, 0) > 0, "the red carpet went back: " + sim.bot.droppedItems);
        assertTrue(sim.bot.droppedItems.getOrDefault("minecraft:rotten_flesh", 0) > 0, sim.bot.droppedItems.toString());
        assertTrue(sim.bot.droppedItems.getOrDefault("minecraft:ominous_bottle", 0) > 0, sim.bot.droppedItems.toString());
        assertTrue(!sim.bot.droppedItems.containsKey(LIME), "the wanted item is kept: " + sim.bot.droppedItems);
        assertTrue(sim.bot.dropStands.contains(PICKUP), "thrown back from the edge: " + sim.bot.dropStands);
        assertTrue(sim.bot.dropStands.contains(JUNK_STAND), "junk at the ocean spot: " + sim.bot.dropStands);
        assertTrue(said(log, "throwing back what no load needs"), log.lines.toString());
    }

    @Test
    void withTheSwitchOffNothingIsThrownBackAtTheEdge() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.config.build.pickupThrowBack = false;
        sim.bot.pickupTrickle.put(LIME, 200);
        sim.bot.pickupTrickle.put(RED, 20);
        Job job = sim.addJob("j0991", "NoBack", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(!sim.bot.dropStands.contains(PICKUP), "no throw at the edge: " + sim.bot.dropStands);
        assertTrue(!said(log, "throwing back"), log.lines.toString());
    }

    @Test
    void aStalledStandGoesBackToThePrimeCellAndAgainThenIsCovered() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.sorterPickupPrime = PRIME;
        sim.config.build.sorterPickupStallSeconds = 3;
        sim.bot.pickupTrickle.put(LIME, 200);
        sim.bot.pickupTrickleAfterReprimes = 1; // the sorter's far part has unloaded after the first trip to the middle
        Job job = sim.addJob("j0992", "Reprime", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(List.of("covered"), sim.bot.pickupReasons);
        assertEquals(1, sim.bot.pickupReprimes, "one re-prime, then the items came");
    }

    @Test
    void theReprimesAreCappedAndThenTheStandStalls() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.sorterPickupPrime = PRIME;
        sim.config.build.sorterPickupStallSeconds = 2;
        sim.config.build.sorterPickupMaxSeconds = 600;
        Job job = sim.addJob("j0993", "Cap", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.pickupReasons.isEmpty(), LONG), sim.state() + log.lines);
        assertEquals("stalled", sim.bot.pickupReasons.getFirst());
        assertEquals(2, sim.bot.pickupReprimes, "build.sorterPickupReprimes");
    }

    @Test
    void theMaxTimeCountsTheReprimeWalks() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = pickupSim(log, true);
        sim.moss.sorterPickupPrime = PRIME;
        sim.config.build.sorterPickupStallSeconds = 3; // 60 ticks, then 80 ticks of walking: 140 > 6 s
        sim.config.build.sorterPickupMaxSeconds = 6;
        Job job = sim.addJob("j0994", "Time", 1, 1, List.of("moss"), (x, z) -> limeRows());
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.pickupReasons.isEmpty(), LONG), sim.state() + log.lines);
        assertEquals("time", sim.bot.pickupReasons.getFirst());
        assertEquals(1, sim.bot.pickupReprimes);
    }
}
