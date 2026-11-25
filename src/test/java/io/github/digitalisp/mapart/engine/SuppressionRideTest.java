package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.render.SuppressionMaps;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour-suppression ride (docs/COLOR_SUPPRESSION.md) in the simulated site: after the last pass the map is
 * filled and checked against the fill's colours, the machine guard and the stasis pearls are read, the bot boards and
 * rides (launch once), checks the finished map, locks, renames and stores it, and waits for the muncher to reset
 * before the job is DONE. Anything unexpected is BLOCKED and the bot does nothing more.
 */
class SuppressionRideTest {
    private static final int LONG = 80_000;
    static final Pos RAIL = new Pos(-66, 120, -70);
    static final Pos STAND = new Pos(-67, 118, -71);
    static final Pos BUTTON = new Pos(-66, 123, -68);
    static final Pos BRAKE = new Pos(-130, 118, -70);
    static final Pos MACHINE = new Pos(-70, 97, -50);
    static final Pos TRAPDOOR = new Pos(-80, 90, -80);

    private static MapArtConfig.RegionCfg box(Pos a, Pos b, String name) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = a;
        r.max = b;
        r.name = name;
        return r;
    }

    /** A CS platform that rides, with a machine block to guard and one stasis cell with its pearl. */
    static Sim rideSim(SuppressionArt a) {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(sim);
        MapArtConfig.RideCfg r = cs.ride;
        r.enabled = true;
        r.startRail = RAIL;
        r.startStand = STAND;
        r.button = BUTTON;
        r.brake = BRAKE;
        r.cartographyTable = Sim.TABLE;
        r.anvil = Sim.ANVIL;
        r.access.add(box(new Pos(-140, 110, -80), new Pos(-60, 125, -66), "ride"));
        r.machine.add(box(new Pos(-72, 95, -55), new Pos(-66, 99, -45), "units"));
        r.viewpoints.add(STAND);
        r.stasis.add(box(new Pos(-85, 88, -85), new Pos(-75, 92, -75), "stasis"));
        r.stasisCells = 1;
        r.settleMinutes = 1;
        r.waitForReset = true; // the reset wait as before; off (the default) in the tests of ride.waitForReset
        sim.world.set(MACHINE, BlockSpec.of("minecraft:honey_block"));
        sim.world.set(MACHINE.offset(1, 0, 0), BlockSpec.of("minecraft:slime_block"));
        sim.world.set(TRAPDOOR, BlockSpec.of("minecraft:dark_oak_trapdoor"));
        sim.world.set(TRAPDOOR.offset(0, -1, 0), BlockSpec.of("minecraft:air"));
        sim.world.set(TRAPDOOR.offset(0, -2, 0), BlockSpec.of("minecraft:bubble_column"));
        sim.world.things.add(new WorldView.Thing(77, "ender_pearl", TRAPDOOR.x() + 0.5, TRAPDOOR.y() - 0.8,
            TRAPDOOR.z() + 0.5));
        sim.inv.add("minecraft:minecart", 1);
        sim.bot.mapRender = p -> SuppressionMaps.afterFill(a, sim.catalog);
        sim.bot.rideColors = () -> SuppressionMaps.expected(a, sim.catalog);
        // the fake muncher: both layers empty after the ride
        sim.bot.afterRide = () -> clearLayers(sim, cs);
        return sim;
    }

    static void clearLayers(Sim sim, MapArtConfig.PlatformCfg cs) {
        for (int g : cs.glassY)
            for (int y = g + 1; y <= g + 2; y++)
                for (int z = cs.minZ - 1; z < cs.minZ + 128; z++)
                    for (int x = cs.minX; x < cs.minX + 128; x++) sim.world.set(new Pos(x, y, z), null);
    }

    @Test
    void theWholeMapFromPrintToStoredLockedMapAndMuncherReset() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        List<Phase> seen = new ArrayList<>();
        List<EngineControl.EngineStatus> launched = new ArrayList<>();
        Runnable muncher = sim.bot.afterRide;
        sim.bot.afterRide = () -> {
            launched.add(sim.engine.status()); // in the cart, after the launch (the fake ride ends in the same tick)
            muncher.run();
        };
        assertTrue(sim.runUntil(() -> {
            Phase p = sim.phase();
            if (seen.isEmpty() || seen.getLast() != p) seen.add(p);
            assertTrue(p != Phase.BLOCKED, sim.state());
            assertTrue(p != Phase.NUKE && p != Phase.CLEAR, "never nuked or cleared: " + p);
            EngineControl.EngineStatus s = sim.engine.status();
            if (s.rideStartedAt() > 0) assertEquals(Phase.RIDE, p, "only while riding");
            // the ETA of the print is over the whole map and counts the ride in
            if (s.etaSeconds() >= 0) assertTrue(p == Phase.RESTOCK || p == Phase.BUILD || p == Phase.VERIFY, p.name());
            if (s.etaSeconds() >= 0) assertTrue(s.etaSeconds() >= 75 * 60, "the ride is part of the ETA: " + s.etaSeconds());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        // the status carries the ride's launch and its expected length only while the bot rides (docs: the presence
        // shows "41 of ~75 min"); the tile keeps both ends of the ride for the next ride's estimate
        assertEquals(1, launched.size());
        for (EngineControl.EngineStatus s : launched) {
            assertEquals(Phase.RIDE, s.phase());
            assertTrue(s.rideStartedAt() > 0, "the ride's launch time is in the status");
            assertEquals(75 * 60, s.rideExpectedSeconds(), "ride.expectedMinutes, no ride on record yet");
            assertEquals(job.tiles.getLast().rideStartedAt, s.rideStartedAt());
        }
        assertTrue(job.tiles.getLast().rideEndedAt >= job.tiles.getLast().rideStartedAt, "the brake is on record");
        assertTrue(seen.containsAll(List.of(Phase.FILL_MAP, Phase.RIDE_BOARD, Phase.RIDE,
            Phase.RIDE_END, Phase.LOCK, Phase.RENAME, Phase.STORE, Phase.RIDE_RESET)), seen.toString());
        assertTrue(seen.indexOf(Phase.RIDE_BOARD) < seen.indexOf(Phase.RIDE)
            && seen.indexOf(Phase.RIDE) < seen.indexOf(Phase.LOCK)
            && seen.indexOf(Phase.STORE) < seen.indexOf(Phase.RIDE_RESET), seen.toString());
        assertEquals(1, sim.bot.rides);
        assertEquals(1, sim.bot.launches, "the launch button once");
        assertEquals(1, sim.bot.cartsBroken, "the cart taken off the brake");
        Job.TileState last = job.tiles.getLast();
        assertEquals(TileStatus.STORED, last.status);
        assertTrue(last.ridePressed && last.rideSettled);
        assertTrue(sim.notifier.has("info", "Machine reference recorded"), sim.notifier.toString());
        assertTrue(sim.notifier.has("image", "Map done"), sim.notifier.toString());
        assertTrue(sim.notifier.has("info", "Job done"), sim.notifier.toString());
        assertFalse(sim.notifier.has("info", "Ready for the ride"), "the ride is not left to a human");
        // one map per job: its name and its "Map done" line carry no pass coordinate (owner 2026-11-13: "7,0")
        assertEquals(job.name, last.mapName);
        String posts = sim.notifier.toString();
        String done = posts.lines().filter(l -> l.contains("Map done")).findFirst().orElseThrow();
        assertTrue(done.contains("Map done: " + job.name + " | " + job.name + " on cs") && done.contains("1 of 1 map done"), done);
        assertFalse(done.contains(", tile") || done.contains((job.tiles.size() - 1) + ",0"), done);
        // the stored map is the locked copy of the ridden one, showing the finished art
        int locked = sim.maps.locked.iterator().next();
        assertEquals(java.util.Arrays.toString(SuppressionMaps.expected(a, sim.catalog)),
            java.util.Arrays.toString(sim.maps.colors.get(locked)));
    }

    // ---- ride.reuseCart (owner, 2026-11-13): the platform's own cart, broken after the ride and put back at the start ----

    static Sim reuseSim(SuppressionArt a) {
        Sim sim = rideSim(a);
        sim.inv.remove("minecraft:minecart", 1);
        sim.bot.cartOnRail = true;
        sim.config.site.platforms.getLast().ride.reuseCart = true;
        return sim;
    }

    @Test
    void thePlatformsOwnCartIsBrokenAfterTheRideAndPutBackOnTheStartRailBeforeTheMapIsStored() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        List<String> order = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            Job.TileState t = job.tiles.getLast();
            if (t.mapName != null && !order.contains("named")) order.add("named");
            if (sim.bot.cartsPlaced == 1 && !order.contains("placed")) order.add("placed");
            if (t.status == TileStatus.STORED && !order.contains("stored")) order.add("stored");
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("board", "ride", "break", "place"), sim.bot.rideEvents);
        assertEquals(List.of("named", "placed", "stored"), order, "after the name, before the store");
        assertEquals(1, sim.bot.cartsBroken, "the cart taken off the brake");
        assertEquals(1, sim.bot.cartsPlaced, "put back once");
        assertTrue(sim.bot.cartOnRail, "a cart stands on the start rail for the next ride");
        assertEquals(0, sim.inv.count("minecraft:minecart"), "no minecart is left in the inventory");
        Job.TileState last = job.tiles.getLast();
        assertTrue(last.cartReturnDue && last.cartReturned);
        assertEquals(TileStatus.STORED, last.status);
        assertFalse(sim.notifier.toString().contains("not back on the start rail"), sim.notifier.toString());
    }

    @Test
    void aRestartBetweenTheBreakAndThePlacingPutsTheCartBackOnce() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.getLast().cartReturnDue && sim.phase() == Phase.LOCK, LONG), sim.state());
        assertEquals(0, sim.bot.cartsPlaced);
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.cartsPlaced == 1, LONG), sim.state());
        sim.newEngine(); // and one right after the placing: never a second cart
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.cartsPlaced, "placed once");
        assertEquals(1, sim.bot.cartsBroken);
        assertEquals(1, sim.bot.launches);
        assertEquals(0, sim.inv.count("minecraft:minecart"));
    }

    @Test
    void aCartNotPickedUpAfterTheBreakIsAnAlertAndTheMapIsStoredAllTheSame() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.cartLostOnBreak = true;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.notifier.has("alert", "Minecart not picked up"), sim.notifier.toString());
        assertEquals(0, sim.bot.cartsPlaced);
        assertFalse(sim.bot.cartOnRail);
        assertEquals(TileStatus.STORED, job.tiles.getLast().status);
        assertTrue(job.tiles.getLast().cartReturned, "the step is over: not tried again");
    }

    @Test
    void aCartAlreadyOnTheStartRailIsLeftAndTheCarriedOneKept() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        // someone puts a cart on the start rail while the bot rides
        assertTrue(sim.runUntil(() -> job.tiles.getLast().cartReturnDue, LONG), sim.state());
        sim.bot.cartOnRail = true;
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(0, sim.bot.cartsPlaced, "nothing placed");
        assertEquals(1, sim.inv.count("minecraft:minecart"), "the carried minecart is kept, never deposited or thrown away");
        assertEquals(TileStatus.STORED, job.tiles.getLast().status);
    }

    @Test
    void aCartThatCannotBePutBackIsAnAlertTheMapIsStoredAndTheNextRidePlacesAndBoardsIt() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.placeCartFails = "no minecart appeared on the start rail after 3 clicks";
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return first.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.notifier.has("alert", "Cart not back on the start rail"), sim.notifier.toString());
        assertTrue(sim.notifier.toString().contains("The cart is not back on the start rail: ") && sim.notifier.toString().contains("no minecart appeared"),
            sim.notifier.toString());
        assertEquals(TileStatus.STORED, first.tiles.getLast().status);
        assertEquals(1, sim.inv.count("minecraft:minecart"), "kept in the inventory");
        assertFalse(sim.bot.cartOnRail);
        // the next map: no cart on the start rail, one carried: placed and boarded, not BLOCKED
        Job second = sim.addJob("j0002", "cs2", first.tiles.size(), 1, List.of("cs"), (x, z) -> a.passes().get(x).art());
        second.suppression = SuppressionEngineTest.passList(a); // as uploaded: the order of its own tiles
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return second.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertTrue(sim.bot.rideEvents.contains("place+board"), sim.bot.rideEvents.toString());
        assertTrue(sim.bot.cartOnRail, "and put back after the second ride");
        assertEquals(0, sim.inv.count("minecraft:minecart"));
    }

    @Test
    void withoutReuseCartTheBotsOwnCartIsTakenOffTheBrakeAndNeverPutBack() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of("place+board", "ride", "break"), sim.bot.rideEvents);
        assertEquals(0, sim.bot.cartsPlaced);
        assertFalse(job.tiles.getLast().cartReturnDue);
        assertEquals(1, sim.inv.count("minecraft:minecart"), "its own cart, carried on");
    }

    @Test
    void aMachineNotBackHomeAfterTheRideIsBlockedAndNothingMoreIsDone() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        sim.bot.afterRide = () -> {
            clearLayers(sim, cs);
            sim.world.set(MACHINE, null); // a flying machine stuck elsewhere
            sim.world.set(MACHINE.offset(0, 1, 0), BlockSpec.of("minecraft:honey_block"));
        };
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("did not reset") && note.contains("machine guard") && note.contains("honey_block"), note);
        assertEquals(TileStatus.STORED, job.tiles.getLast().status, "the map itself is stored");
        assertFalse(job.tiles.getLast().rideSettled);
        assertTrue(job.status != JobStatus.DONE);
        int rides = sim.bot.rides;
        sim.run(2000);
        assertEquals(Phase.BLOCKED, sim.phase());
        assertEquals(rides, sim.bot.rides, "nothing more is done to the machinery");
    }

    @Test
    void aStasisCellWithoutItsPearlStopsTheRideBeforeTheLaunch() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.world.things.clear();
        SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("stasis") && note.contains("no ender pearl"), note);
        assertEquals(0, sim.bot.rides);
        assertEquals(0, sim.bot.launches);
    }

    @Test
    void aMachineDifferentBeforeTheRideStopsIt() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        // between the maps a unit got stuck: the next map's guard reading differs from the reference
        sim.world.set(MACHINE.offset(1, 0, 0), null);
        sim.inv.add("minecraft:minecart", 1);
        Job second = sim.addJob("j0002", "cs2", first.tiles.size(), 1, List.of("cs"),
            (x, z) -> a.passes().get(x).art());
        second.suppression = SuppressionEngineTest.passList(a); // as uploaded: the order of its own tiles
        int rides = sim.bot.rides;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("machine guard before the ride") && note.contains("slime_block"), note);
        assertEquals(rides, sim.bot.rides, "no second ride");
        assertTrue(cs.ride.enabled);
    }

    @Test
    void aRideThatStallsIsBlockedWithoutARetry() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.bot.rideFails = "the cart has not moved for 600 s at -100,118,-60";
        SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("the ride stopped") && note.contains("has not moved"), note);
        assertEquals(1, sim.bot.rides, "no retry");
        sim.run(2000);
        assertEquals(1, sim.bot.rides);
    }

    // ---- store, count, then the reset wait (owner 2026-11-13: the site's output chest, the map counter pressed) ----

    static final Pos COUNTER = new Pos(-55, 62, 101);

    static Sim counterSim(SuppressionArt a) {
        Sim sim = reuseSim(a);
        sim.config.site.mapCounter = new MapArtConfig.ActuatorCfg();
        sim.config.site.mapCounterColumn = new MapArtConfig.ColumnCfg(COUNTER.x(), COUNTER.z());
        sim.world.set(COUNTER, BlockSpec.of("minecraft:oak_button", java.util.Map.of("face", "wall", "facing", "south",
            "powered", "false")));
        return sim;
    }

    private static List<String> phases(Sim sim) {
        List<String> out = new ArrayList<>();
        for (String p : sim.store.phaseLog) if (out.isEmpty() || !out.getLast().equals(p)) out.add(p);
        return out;
    }

    @Test
    void theRiddenMapIsStoredInTheSitesOutputChestCountedAndOnlyThenWaitsForTheMuncher() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = counterSim(a);
        List<Integer> storedAtPress = new ArrayList<>();
        sim.bot.useHandlers.put(COUNTER, () -> storedAtPress.add(sim.output().maps.size()));
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        List<String> order = phases(sim);
        int store = order.lastIndexOf("STORE"), count = order.lastIndexOf("COUNT"), reset = order.lastIndexOf("RIDE_RESET");
        assertTrue(order.indexOf("RENAME") < store && store < count && count < reset, order.toString());
        assertEquals(1, java.util.Collections.frequency(order, "COUNT"), order.toString());
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses, "the counter once");
        assertEquals(List.of(1), storedAtPress, "pressed with the map in the site's output chest");
        assertEquals(1, sim.output().maps.size());
        assertEquals("place", sim.bot.rideEvents.getLast(), "the cart went back before the store");
        Job.TileState last = job.tiles.getLast();
        assertTrue(last.counted && last.rideSettled);
    }

    @Test
    void aResetThatBlocksLeavesTheMapStoredAndCounted() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = counterSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        sim.bot.afterRide = () -> {
            clearLayers(sim, cs);
            sim.world.set(MACHINE, null);
            sim.world.set(MACHINE.offset(0, 1, 0), BlockSpec.of("minecraft:honey_block"));
        };
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("did not reset"), sim.engine.status().note());
        assertEquals(TileStatus.STORED, job.tiles.getLast().status);
        assertTrue(job.tiles.getLast().counted, "counted before the wait");
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses);
    }

    @Test
    void aRestartAfterTheStoreCountsThenWaitsAndOneAfterTheCountOnlyWaits() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = counterSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        Job.TileState last = job.tiles.getLast();
        assertTrue(sim.runUntil(() -> last.status == TileStatus.STORED, LONG), sim.state());
        assertFalse(last.counted);
        sim.newEngine(); // stored, not counted
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.RIDE_RESET || last.counted, "no reset wait before the count");
            return last.counted;
        }, LONG), sim.state());
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses);
        assertFalse(last.rideSettled);
        sim.newEngine(); // stored and counted
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RIDE_RESET, 2000), sim.state());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses, "never a second press");
        assertTrue(last.rideSettled);
        assertEquals(1, sim.output().maps.size());
    }

    // ---- top layer first, a layer still being cleared, no reset wait (owner 2026-11-14) ----

    /** The ride sim as it is live from now on: the top layer's passes first, no reset wait, the platform's own cart. */
    static Sim topFirstSim(SuppressionArt a) {
        a.topFirst(true);
        Sim sim = counterSim(a);
        sim.config.site.platforms.getLast().ride.waitForReset = false;
        return sim;
    }

    /** Blocks of a map before on the bottom layer (the muncher has not cleared them yet). */
    static void lastMapOnTheBottomLayer(Sim sim, int n) {
        for (int i = 0; i < n; i++) sim.world.set(new Pos(-60 + i, 101, 40), BlockSpec.of("minecraft:stone"));
    }

    static int firstBottomPass(Job job) {
        for (int i = 0; i < job.suppression.size(); i++) if (job.suppression.get(i).layer == SuppressionArt.BOTTOM) return i;
        return -1;
    }

    @Test
    void aNewJobPrintsTheTopLayerFirstAndIsDoneOnceTheMapIsStoredAndCounted() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        int half = firstBottomPass(job);
        assertEquals(4, half, "the four top passes first");
        for (int i = 0; i < job.suppression.size(); i++)
            assertEquals(i < half ? SuppressionArt.TOP : SuppressionArt.BOTTOM, job.suppression.get(i).layer, "pass " + i);
        sim.start();
        List<Integer> built = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            var st = sim.engine.status();
            if (st.jobId() != null && (built.isEmpty() || built.getLast() != st.tileX())) built.add(st.tileX());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), built, "the passes in their stored order");
        List<String> order = phases(sim);
        assertFalse(order.contains("RIDE_RESET"), "no reset wait: " + order);
        assertTrue(order.lastIndexOf("STORE") < order.lastIndexOf("COUNT"), order.toString());
        Job.TileState last = job.tiles.getLast();
        assertTrue(last.counted && last.ridePressed);
        assertFalse(last.rideSettled, "the reset was not waited for");
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses);
        assertEquals(1, sim.bot.rides);
        assertTrue(sim.notifier.has("info", "Job done"), sim.notifier.toString());
        assertFalse(sim.notifier.has("info", "Ready for the ride"), "a ridden map is DONE, not left for a ride");
        assertFalse(sim.notifier.toString().contains("the muncher reset"), "nothing is said of a reset nobody saw");
        int locked = sim.maps.locked.iterator().next();
        assertEquals(java.util.Arrays.toString(SuppressionMaps.expected(a, sim.catalog)),
            java.util.Arrays.toString(sim.maps.colors.get(locked)));
    }

    @Test
    void aRestartAfterTheStoreOfAJobWithoutTheResetWaitNeverGoesToTheResetWait() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.getLast().status == TileStatus.STORED, LONG), sim.state());
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.RIDE_RESET && sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of(COUNTER), sim.bot.buttonPresses, "counted once");
        assertEquals(1, sim.bot.rides);
    }

    @Test
    void theTopPassesStartWhileTheBottomLayerIsStillBeingClearedAndTheBottomPassWaitsForIt() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        lastMapOnTheBottomLayer(sim, 40);
        Job job = SuppressionEngineTest.addJob(sim, a);
        int bottom = firstBottomPass(job);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.engine.status().note().startsWith("waiting for the muncher to clear the bottom layer");
        }, LONG), sim.state());
        assertEquals("waiting for the muncher to clear the bottom layer: 40 blocks left", sim.engine.status().note());
        assertEquals(bottom, sim.engine.status().tileX(), "the first bottom pass waits");
        for (int i = 0; i < bottom; i++)
            assertEquals(job.tiles.get(i).total, job.tiles.get(i).placed, "top pass " + i + " was printed meanwhile");
        assertTrue(sim.world.playerBlock().y() > 110, "the bot waits off the bottom layer: " + sim.world.playerBlock());
        assertEquals(0, job.tiles.get(bottom).placed);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        // the muncher goes on clearing, slower than the limit of a minute between two looks would allow in one go
        for (int left = 30; left >= 0; left -= 10) {
            for (int i = left; i < left + 10; i++) sim.world.set(new Pos(-60 + i, 101, 40), null);
            sim.run(900);
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
        }
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.rides);
    }

    @Test
    void aBottomLayerWhoseCountStopsGoingDownIsBlockedNotReset() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        lastMapOnTheBottomLayer(sim, 40);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.engine.status().note().startsWith("waiting for the muncher"), LONG), sim.state());
        for (int i = 0; i < 10; i++) sim.world.set(new Pos(-60 + i, 101, 40), null); // it went down once, then stopped
        sim.run(800);
        assertEquals("waiting for the muncher to clear the bottom layer: 30 blocks left", sim.engine.status().note());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 3000), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("is not reset") && note.contains("30 blocks on the bottom layer")
            && note.contains("has not gone down for 1 min"), note);
        assertEquals(0, job.tiles.get(firstBottomPass(job)).placed, "nothing was placed on it");
        assertEquals(0, sim.bot.rides);
    }

    @Test
    void machinesStillOnTheirWayHomeBeforeTheNextRideAreWaitedFor() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        // a unit of the last ride is not home yet when the next map is printed and ready
        sim.world.set(MACHINE.offset(1, 0, 0), null);
        Job second = sim.addJob("j0002", "cs2", first.tiles.size(), 1, List.of("cs"), (x, z) -> a.passes().get(x).art());
        second.suppression = SuppressionEngineTest.passList(a); // as uploaded: the order of its own tiles
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.engine.status().note().startsWith("waiting for the machines to come home");
        }, LONG), sim.state());
        assertEquals(1, sim.bot.rides, "no second ride yet");
        sim.world.set(MACHINE.offset(1, 0, 0), BlockSpec.of("minecraft:slime_block")); // home
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return second.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
    }

    /** A second viewpoint, away from the start stand: every walk of the viewpoints goes there first. */
    static final Pos FAR_VIEW = new Pos(-100, 118, -71);

    /** A second job on the platform of {@code first}, with the same art. */
    private static Job secondJob(Sim sim, SuppressionArt a, Job first) {
        Job second = sim.addJob("j0002", "cs2", first.tiles.size(), 1, List.of("cs"), (x, z) -> a.passes().get(x).art());
        second.suppression = SuppressionEngineTest.passList(a); // as uploaded: the order of its own tiles
        return second;
    }

    /**
     * Live 2026-11-14: 64 cells of the machinery had changed for good (white concrete to white stained glass panes);
     * the pre-ride guard read the same difference every 20 s for 35 minutes, walking every viewpoint each time, before
     * it went BLOCKED. A difference that does not change is not machines on their way home: BLOCKED after
     * {@code ride.guardConfirmMinutes}, and the looks in between are taken from the nearest viewpoint that sees the
     * differing chunks.
     */
    @Test
    void aGuardDifferenceThatNeverChangesIsBlockedAfterTheConfirmTimeWithoutWalkingTheViewpointsAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.settleMinutes = 30;
        r.guardConfirmMinutes = 2;
        r.viewpoints.add(0, FAR_VIEW);
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        sim.world.set(MACHINE, BlockSpec.of("minecraft:white_stained_glass_pane")); // changed for good
        secondJob(sim, a, first);
        int rides = sim.bot.rides;
        int[] ticks = {0, -1, -1};
        int[] walksAtFirstLook = {-1};
        assertTrue(sim.runUntil(() -> {
            ticks[0]++;
            String note = sim.engine.status().note();
            if (ticks[1] < 0 && note != null && note.startsWith(BuildEngine.GUARD_WAIT_NOTE)) {
                ticks[1] = ticks[0];
                walksAtFirstLook[0] = sim.bot.history.size();
            }
            return sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state());
        assertTrue(ticks[1] > 0, "it waited first");
        int waited = ticks[0] - ticks[1];
        assertTrue(waited >= 2 * 1200, "not before the confirm time: " + waited + " ticks");
        assertTrue(waited <= 2 * 1200 + 2 * BuildEngine.SETTLE_POLL_TICKS + 200, "at once after it, not after the 30"
            + " minutes of ride.settleMinutes: " + waited + " ticks");
        String note = sim.engine.status().note();
        assertTrue(note.contains("machine guard before the ride") && note.contains("glass_pane")
            && note.contains("The muncher is not as recorded"), note);
        assertEquals(rides, sim.bot.rides, "no second ride");
        List<String> after = sim.bot.history.subList(walksAtFirstLook[0], sim.bot.history.size());
        assertFalse(after.stream().anyMatch(h -> h.startsWith("walkTo")), "the looks after the first were taken from where"
            + " the bot stood, no viewpoint walked again: " + after);
    }

    /**
     * Live 2026-11-16: the booth's anvil stands inside the muncher guard box; it broke at the seventh name, the guard
     * before the next ride read "anvil -> air" and went BLOCKED with a finished print on the platform. The anvil is
     * the bot's own working item, not machinery: its cell is not guarded, the ride goes on, and RENAME places the next
     * anvil from the anvil chests.
     */
    @Test
    void theBoothsBrokenAnvilIsNotAMachineDifference() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.machine.add(box(Sim.ANVIL, Sim.ANVIL.offset(0, 1, 0), "booth"));
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        sim.world.set(Sim.ANVIL, null); // it broke at the last name
        Job second = secondJob(sim, a, first);
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return second.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertTrue(sim.world.block(Sim.ANVIL).id().endsWith("anvil"), "RENAME placed the next anvil: "
            + sim.world.block(Sim.ANVIL).id());
    }

    /**
     * Live 2026-11-15 and 2026-11-16: the owner built inside the machine boxes, the guard went BLOCKED and its message
     * said "delete blobs/cs-machine-cs.bin to record it again, then resume"; the reference stayed in memory, so only a
     * proxy restart recorded it again. Now the deleted blob is noticed at the resume: the next reading is the new
     * reference and the ride goes on.
     */
    @Test
    void aDeletedGuardReferenceIsRecordedAgainAtTheResumeWithoutARestart() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        sim.store.blobs = new java.util.HashMap<>();
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.settleMinutes = 30;
        r.guardConfirmMinutes = 1;
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.store.blobs.containsKey("cs-machine-cs.bin"), "the reference is in the store: " + sim.store.blobs.keySet());
        Pos built = MACHINE.offset(0, 1, 0);
        sim.world.set(built, BlockSpec.of("minecraft:white_stained_glass_pane")); // built by hand, for good
        Job second = secondJob(sim, a, first);
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("machine guard before the ride") && note.contains("glass_pane"), note);
        assertEquals(1, sim.bot.rides, "no second ride yet");
        sim.engine.resume(); // the reference is still there: BLOCKED again, as before
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.BLOCKED, 200), sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertEquals(1, sim.bot.rides, "still no second ride");
        sim.store.blobs.remove("cs-machine-cs.bin"); // what the message says
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.BLOCKED, 200), sim.state());
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return second.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertTrue(sim.store.blobs.containsKey("cs-machine-cs.bin"), "the new reference is in the store");
    }

    /** Machines still moving: the difference is another one at every look, and the wait goes on past the confirm time. */
    @Test
    void aGuardDifferenceThatKeepsChangingIsWaitedForPastTheConfirmTime() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.settleMinutes = 30;
        r.guardConfirmMinutes = 1;
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        sim.world.set(MACHINE.offset(1, 0, 0), null); // a unit not home yet
        Job second = secondJob(sim, a, first);
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.engine.status().note().startsWith(BuildEngine.GUARD_WAIT_NOTE);
        }, LONG), sim.state());
        // it moves on: one more cell of the machine box differs every 200 ticks (a look comes every 400)
        List<Pos> moved = new ArrayList<>();
        for (int t = 0; t < 4 * 1200; t++) {
            if (t % 200 == 0) {
                Pos p = new Pos(-72 + moved.size() % 7, 95, -55 + moved.size() / 7);
                sim.world.set(p, BlockSpec.of("minecraft:slime_block"));
                moved.add(p);
            }
            sim.run(1);
            assertEquals(Phase.RIDE_BOARD, sim.phase(), "still waiting after " + t + " ticks: " + sim.state());
        }
        assertEquals(1, sim.bot.rides, "no second ride yet");
        // home
        for (Pos p : moved) sim.world.set(p, null);
        sim.world.set(MACHINE.offset(1, 0, 0), BlockSpec.of("minecraft:slime_block"));
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return second.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
    }

    /**
     * The pre-ride guard on a difference that never changes, with the machine chunks in view as {@code loaded} says:
     * runs to BLOCKED and returns the bot's tasks from the first look on.
     */
    private static List<String> looksUntilBlocked(java.util.function.BiFunction<Sim, Pos, Boolean> loaded, Pos... changed) {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = topFirstSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.settleMinutes = 30;
        r.guardConfirmMinutes = 1;
        r.viewpoints.add(0, FAR_VIEW);
        Job first = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        sim.world.loaded = p -> loaded.apply(sim, p);
        for (Pos p : changed) sim.world.set(p, BlockSpec.of("minecraft:white_stained_glass_pane"));
        secondJob(sim, a, first);
        int[] ticks = {0, -1};
        int[] atFirstLook = {-1};
        assertTrue(sim.runUntil(() -> {
            ticks[0]++;
            String note = sim.engine.status().note();
            if (ticks[1] < 0 && note != null && note.startsWith(BuildEngine.GUARD_WAIT_NOTE)) {
                ticks[1] = ticks[0];
                atFirstLook[0] = sim.bot.history.size();
            }
            return sim.phase() == Phase.BLOCKED;
        }, LONG), sim.state());
        assertTrue(ticks[1] > 0, sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("machine guard before the ride") && note.contains("glass_pane"), note);
        assertTrue(ticks[0] - ticks[1] >= 1200 && ticks[0] - ticks[1] < 5 * 1200,
            "blocked by the unchanged difference, not after 30 minutes: " + (ticks[0] - ticks[1]) + " ticks");
        return List.copyOf(sim.bot.history.subList(atFirstLook[0], sim.bot.history.size()));
    }

    private static boolean machineChunk(Pos p) {
        return (p.x() >> 4) == (MACHINE.x() >> 4) && (p.z() >> 4) == (MACHINE.z() >> 4);
    }

    /** The differing chunk is in view only from the far viewpoint: one walk there, and every further look from it. */
    @Test
    void theGuardIsReadAgainFromTheNearestViewpointThatSeesTheDifference() {
        List<String> after = looksUntilBlocked((sim, p) -> !machineChunk(p) || sim.world.x < -90, MACHINE);
        assertEquals(1, after.stream().filter(h -> h.equals("walkTo " + FAR_VIEW)).count(), "one walk to it: " + after);
        assertFalse(after.stream().anyMatch(h -> h.equals("walkTo " + STAND)), "the other viewpoint is not walked again: " + after);
    }

    /** Two differing chunks, each in view from another viewpoint: no single one sees both, so the whole walk as before. */
    @Test
    void aGuardDifferenceNoSingleViewpointSeesIsReadByTheWholeWalkAndBlockedAllTheSame() {
        Pos other = new Pos(MACHINE.x(), MACHINE.y(), -46); // the machine box's other chunk
        assertFalse(machineChunk(other));
        boolean[] seen = {false};
        List<String> after = looksUntilBlocked((sim, p) -> {
            boolean far = sim.world.x < -90;
            if (machineChunk(p)) return far;
            if ((p.x() >> 4) == (other.x() >> 4) && (p.z() >> 4) == (other.z() >> 4)) return !far;
            return true;
        }, MACHINE, other);
        seen[0] = after.stream().filter(h -> h.equals("walkTo " + FAR_VIEW)).count() >= 2
            && after.stream().filter(h -> h.equals("walkTo " + STAND)).count() >= 2;
        assertTrue(seen[0], "both viewpoints walked for every look: " + after);
    }

    // ---- the preload lap (ride.preload, owner 2026-11-13) ----

    static final Pos LAP_RAIL = new Pos(-70, 118, -74);
    static final Pos LAP_STAND = new Pos(-69, 118, -74);
    static final Pos LAP_BUTTON = new Pos(-69, 119, -76);

    /** {@link #rideSim} with the platform's own carts and a preload lap (a loop: it ends on its start rail). */
    static Sim preloadSim(SuppressionArt a) {
        Sim sim = rideSim(a);
        sim.inv.remove("minecraft:minecart", 1);
        sim.bot.cartOnRail = true;
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.reuseCart = true;
        r.preload = new MapArtConfig.PreloadCfg();
        r.preload.startRail = LAP_RAIL;
        r.preload.startStand = LAP_STAND;
        r.preload.button = LAP_BUTTON;
        return sim;
    }

    @Test
    void thePreloadLapIsRiddenAfterTheChecksAndRightBeforeTheMainCart() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        List<Phase> seen = new ArrayList<>();
        byte[][] onTheLap = new byte[1][];
        assertTrue(sim.runUntil(() -> {
            Phase p = sim.phase();
            if (seen.isEmpty() || seen.getLast() != p) seen.add(p);
            assertTrue(p != Phase.BLOCKED, sim.state());
            if (p == Phase.RIDE_PRELOAD && sim.bot.preloadRides == 1 && onTheLap[0] == null && !sim.maps.colors.isEmpty())
                onTheLap[0] = sim.maps.colors.values().iterator().next().clone();
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "board", "ride", "break", "place"), sim.bot.rideEvents,
            "the lap, then at once the main cart");
        int board = seen.indexOf(Phase.RIDE_BOARD), lap = seen.indexOf(Phase.RIDE_PRELOAD), ride = seen.indexOf(Phase.RIDE);
        assertTrue(seen.indexOf(Phase.CHECK_MAP) < board && board < lap && lap < ride, seen.toString());
        assertEquals(Phase.RIDE_BOARD, seen.get(lap + 1), "back to the boarding after the lap: " + seen);
        assertEquals(1, sim.bot.preloadRides);
        assertEquals(1, sim.bot.preloadLaunches, "the lap's button once");
        assertEquals(1, sim.bot.rides);
        assertEquals(1, sim.bot.launches, "the launch button once");
        assertEquals(1, sim.bot.cartsBroken, "only the main cart, after the ride");
        assertEquals(1, sim.bot.cartsPlaced, "and it is put back on its start rail");
        assertEquals(0, sim.inv.count("minecraft:minecart"));
        assertTrue(sim.notifier.has("info", "Machine reference recorded"), "the checks ran before the lap");
        // the map came through the lap as the fill left it, and the ride then finished it
        assertEquals(java.util.Arrays.toString(SuppressionMaps.afterFill(a, sim.catalog)),
            java.util.Arrays.toString(onTheLap[0]));
        Job.TileState last = job.tiles.getLast();
        assertEquals(TileStatus.STORED, last.status);
        assertEquals(1, last.preloadLaps);
        assertFalse(last.preloadPressed);
        assertTrue(last.ridePressed && last.rideSettled);
        int locked = sim.maps.locked.iterator().next();
        assertEquals(java.util.Arrays.toString(SuppressionMaps.expected(a, sim.catalog)),
            java.util.Arrays.toString(sim.maps.colors.get(locked)));
    }

    @Test
    void aPreloadLapThatStallsIsBlockedAndTheMuncherIsNeverLaunched() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        sim.bot.preloadFails = "the cart has not moved for 120 s at -100,116,-60";
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("the preload lap stopped") && note.contains("has not moved")
            && note.contains("the ride is not started"), note);
        assertEquals(1, sim.bot.preloadRides, "no retry");
        sim.run(2000);
        assertEquals(Phase.BLOCKED, sim.phase());
        assertEquals(1, sim.bot.preloadRides);
        assertEquals(0, sim.bot.rides, "the main cart is never boarded");
        assertEquals(0, sim.bot.launches, "the muncher is never launched");
        assertEquals(List.of("preload-board", "preload"), sim.bot.rideEvents);
        assertFalse(job.tiles.getLast().ridePressed);
    }

    /** Live 2026-11-24, j0040 shrimp [6,0]: no cart on the lap's start rail: a carried spare is put there and ridden. */
    @Test
    void noCartOnThePreloadRailPutsACarriedSpareThere() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        sim.bot.preloadCartOnRail = false;
        sim.inv.add("minecraft:minecart", 1);
        SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.rideEvents.contains("place+board"), LONG), sim.state());
        assertEquals(0, sim.inv.count("minecraft:minecart"));
    }

    /** No cart on the lap's rail, none carried and none in the cart chests: BLOCKED, nothing pressed. */
    @Test
    void noCartOnThePreloadRailAndNoSpareIsBlocked() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        sim.bot.preloadCartOnRail = false;
        SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("the preload lap did not start") && note.contains("minecart"), note);
        assertEquals(0, sim.bot.preloadRides);
        assertEquals(0, sim.bot.rides);
        assertEquals(0, sim.bot.launches);
    }

    /** Preloading is necessary (owner): a lap ridden before a restart does not count for the boarding after it. */
    @Test
    void aRestartBetweenThePreloadLapAndTheMainCartRidesTheLapAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        // stop right after the lap: out of its cart, the main cart not boarded yet
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.bot.preloadRides == 1 && sim.phase() == Phase.RIDE_BOARD && sim.world.vehicle == null;
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload"), sim.bot.rideEvents);
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "preload-board", "preload", "board", "ride", "break", "place"),
            sim.bot.rideEvents, "the lap again after the restart, then the main cart");
        assertEquals(2, sim.bot.preloadLaunches);
        assertEquals(1, sim.bot.launches, "the muncher once");
        assertEquals(2, job.tiles.getLast().preloadLaps);
    }

    /** The same for a reconnect without a restart of the plugin. */
    @Test
    void aReconnectBetweenThePreloadLapAndTheMainCartRidesTheLapAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.preloadRides == 1 && sim.phase() == Phase.RIDE_BOARD
            && sim.world.vehicle == null, LONG), sim.state());
        sim.engine.onDisconnect();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.preloadRides);
        assertEquals(1, sim.bot.rides);
        assertEquals(1, sim.bot.launches, "the muncher once");
        assertEquals(List.of("preload-board", "preload", "preload-board", "preload", "board", "ride", "break", "place"),
            sim.bot.rideEvents);
    }

    /** A restart of the plugin in the lap's cart after its launch: no second press; the lap does not count, so it runs again. */
    @Test
    void aRestartInThePreloadCartAfterItsLaunchRidesOnWithoutASecondPress() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RIDE_PRELOAD && sim.world.vehicle != null, LONG), sim.state());
        assertEquals(0, sim.bot.preloadRides);
        job.tiles.getLast().preloadPressed = true; // the press went out, then the plugin restarted
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.preloadRides == 1, LONG), sim.state());
        assertEquals(0, sim.bot.preloadLaunches, "the lap is ridden on without a press");
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.engine.status().jobId() == null || sim.bot.rides == 1;
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "preload-board", "preload", "board", "ride"), sim.bot.rideEvents,
            "the checks and a whole lap again before the main cart");
        assertEquals(1, sim.bot.preloadLaunches);
        assertEquals(1, sim.bot.launches);
    }

    /** Out of the cart after the lap's launch, away from its start (a reconnect mid-lap): BLOCKED, nothing started. */
    @Test
    void outOfThePreloadCartMidLapIsBlocked() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RIDE_PRELOAD && sim.world.vehicle != null, LONG), sim.state());
        job.tiles.getLast().preloadPressed = true;
        sim.world.vehicle = null;
        sim.world.moveTo(LAP_RAIL.offset(-40, 0, 30)); // somewhere on the track
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("not in the cart after the preload lap's launch") && note.contains("The ride is not started"), note);
        assertEquals(0, sim.bot.rides);
        assertEquals(0, sim.bot.launches);
    }

    /** A restart after the main launch never rides the lap or presses again: out of the cart is BLOCKED as before. */
    @Test
    void aRestartAfterTheMainLaunchNeverRidesTheLapAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        sim.config.site.platforms.getLast().ride.recoverLostRide = false;
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(job.tiles.getLast().ridePressed);
        sim.world.vehicle = null;
        sim.newEngine();
        sim.start();
        sim.engine.resume();
        sim.run(3000);
        assertEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals(1, sim.bot.preloadRides, "no second lap");
        assertEquals(1, sim.bot.launches, "no second launch");
        assertEquals(1, sim.bot.rides);
    }

    /** Live 2026-11-14: a lag teleport put the bot beside its held cart mid-ride; it gets back in and rides on. */
    @Test
    void outOfTheCartMidRideWithTheCartInReachGetsBackIn() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("place+board", "ride", "preload-board", "ride", "break"), sim.bot.rideEvents);
        assertEquals(1, sim.bot.launches, "the launch is never pressed twice");
        assertEquals(2, sim.bot.rides);
    }

    /**
     * Live 2026-11-16: the anti-cheat set the riding bot back to its boarding seat and the server put it out beside its
     * cart; in that tick the client's picture was false (the bot and the cart at the boarding seat), getting back in
     * failed on it and the ride was BLOCKED while the cart went on. The bot does nothing until the picture has settled,
     * then gets back in.
     */
    @Test
    void outOfTheCartOnAFalsePictureWaitsForItToSettleAndGetsBackIn() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.rideFailsUnsettledTicks = 20;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("place+board", "ride", "preload-board", "ride", "break"), sim.bot.rideEvents,
            "one try, after the settle");
        assertEquals(1, sim.bot.launches, "the launch is never pressed twice");
        assertEquals(2, sim.bot.rides);
        assertFalse(job.tiles.getLast().rideLost);
    }

    /** A failed try to get back in is not BLOCKED with the lost ride's recovery on: the next try gets the bot in. */
    @Test
    void aFailedTryToGetBackInIsTriedAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.failNext.put("boardOwnCart", 1);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.launches);
        assertEquals(2, sim.bot.rides);
        assertFalse(job.tiles.getLast().rideLost);
    }

    /** Every try to get back in fails: the ride is lost and its recovery runs; never BLOCKED. */
    @Test
    void whenEveryTryToGetBackInFailsTheLostRidesRecoveryTakesOver() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.config.site.platforms.getLast().ride.expectedMinutes = 1;
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.failNext.put("boardOwnCart", BuildEngine.RIDE_REBOARDS);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.tiles.getLast().rideLost;
        }, LONG), sim.state());
        assertEquals(Phase.RIDE, sim.phase(), "waiting where it stands for the muncher's run to end");
        assertEquals(1, sim.bot.launches);
        assertEquals(1, sim.bot.rides);
    }

    /**
     * Live 2026-11-14: the ride lost (out of the cart, the cart went on with the muncher). The bot waits for the
     * muncher's run to end, takes the cart off the brake, puts it back on the start rail and prints the map again;
     * the second ride ends the job.
     */
    @Test
    void aLostRideIsRecoveredAndTheMapIsPrintedAgain() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.expectedMinutes = 1;
        cs.ride.reuseCart = true; // as live: the platform's own cart goes back on the start rail
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.rideFailsCartInReach = false;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.tiles.getLast().rideLost;
        }, LONG), sim.state());
        assertEquals(Phase.RIDE, sim.phase(), "waiting where it stands for the muncher's run to end");
        // the muncher ends its run without the bot: the layers empty, the empty cart on the brake
        clearLayers(sim, cs);
        sim.bot.cartAtBrake = true;
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertEquals(2, sim.bot.launches, "the second ride is launched like any other");
        assertEquals(List.of("place+board", "ride", "break", "place", "board", "ride", "break", "place"),
            sim.bot.rideEvents, "the cart off the brake and back on the start rail, then the second ride as any other");
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
        assertFalse(job.tiles.getLast().rideLost);
    }

    /** A chest of spare minecarts ({@code ride.cartChests}). */
    static final Pos SPARE_CARTS = new Pos(-40, 61, 100);

    private static void spareCarts(Sim sim) {
        sim.config.site.platforms.getLast().ride.cartChests.add(SPARE_CARTS);
        sim.bot.addContainer(SPARE_CARTS, java.util.Map.of("minecraft:minecart", 2));
    }

    /**
     * Live 2026-11-16: the lost ride's cart came off the brake but its item was not picked up; BLOCKED with the run
     * finished. With {@code ride.reuseCart} and a cart chest the run counts as ended, the map is printed again and the
     * ride's kit takes a spare for the start rail.
     */
    @Test
    void aLostRidesCartThatIsNotPickedUpIsReplacedByASpareFromTheCartChests() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.expectedMinutes = 1;
        cs.ride.reuseCart = true;
        spareCarts(sim);
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.rideFailsCartInReach = false;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.tiles.getLast().rideLost;
        }, LONG), sim.state());
        // the muncher ends its run without the bot; the cart on the brake breaks but its item is not picked up
        clearLayers(sim, cs);
        sim.bot.cartAtBrake = true;
        sim.bot.cartLostOnBreak = true;
        sim.world.things.add(new WorldView.Thing(4242, "minecart", BRAKE.x() + 0.5, BRAKE.y(), BRAKE.z() + 0.5));
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.bot.cartsBroken > 0) {
                sim.world.things.removeIf(t -> t.id() == 4242);
                sim.bot.cartLostOnBreak = false;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.notifier.has("alert", "Minecart lost"), sim.notifier.toString());
        assertFalse(job.lostRunOpen, "the run had ended: no laps");
        assertFalse(job.cartLost, "cleared once the bot sat in the spare");
        assertEquals(2, sim.bot.rides);
        assertTrue(sim.bot.containers.get(SPARE_CARTS).items.getOrDefault("minecraft:minecart", 0) < 2, "a spare was taken");
    }

    /** The same in the lost run's own recovery (the laps, the booth): no BLOCKED at the pick-up, a spare instead. */
    @Test
    void aLostRunsCartThatIsNotPickedUpIsReplacedByASpareFromTheCartChests() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.expectedMinutes = 1;
        cs.ride.reuseCart = true;
        spareCarts(sim);
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.rideFailsCartInReach = false;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.phase() == Phase.RIDE_RESET && sim.engine.status().note().contains("waiting in the booth");
        }, LONG), sim.state());
        clearLayers(sim, cs);
        sim.bot.cartAtBrake = true;
        sim.bot.cartLostOnBreak = true;
        sim.world.things.add(new WorldView.Thing(4242, "minecart", BRAKE.x() + 0.5, BRAKE.y(), BRAKE.z() + 0.5));
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.bot.cartsBroken > 0) {
                sim.world.things.removeIf(t -> t.id() == 4242);
                sim.bot.cartLostOnBreak = false;
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.notifier.has("alert", "Minecart lost"), sim.notifier.toString());
        assertFalse(job.cartLost);
        assertEquals(2, sim.bot.rides);
    }

    /**
     * {@code ride.reuseCart} with no cart on the start rail and none carried (the platform's cart was lost at an earlier
     * ride's end): a spare is fetched once and the bot boards; not BLOCKED.
     */
    @Test
    void withNoCartOnTheStartRailAndNoneCarriedASpareIsFetchedBeforeBoarding() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.cartOnRail = false;
        spareCarts(sim);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.notifier.has("alert", "Minecart lost"), sim.notifier.toString());
        assertEquals("place+board", sim.bot.rideEvents.getFirst());
        assertEquals(1, sim.bot.rides);
        assertFalse(job.cartLost);
    }

    /**
     * Live 2026-11-16: the cart chest was empty. The print goes on, the ride stops at the boarding; a minecart put on
     * the start rail is boarded at the resume.
     */
    @Test
    void withTheCartChestEmptyACartPutOnTheStartRailIsBoardedAtTheResume() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.cartOnRail = false;
        spareCarts(sim);
        sim.bot.containers.get(SPARE_CARTS).items.clear();
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("no minecart carried"), sim.engine.status().note());
        assertTrue(job.cartLost);
        for (int i = 0; i < job.tiles.size() - 1; i++) assertEquals(TileStatus.STORED, job.tiles.get(i).status, "printed first");
        sim.bot.cartOnRail = true;
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.BLOCKED, 200), sim.state());
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals("board", sim.bot.rideEvents.getFirst());
        assertEquals(1, sim.bot.rides);
        assertFalse(job.cartLost);
    }

    /** The same with a minecart put into the cart chest: fetched at the resume, placed and boarded. */
    @Test
    void withTheCartChestEmptyACartPutIntoItIsFetchedAtTheResume() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.cartOnRail = false;
        spareCarts(sim);
        sim.bot.containers.get(SPARE_CARTS).items.clear();
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        sim.bot.containers.get(SPARE_CARTS).items.put("minecraft:minecart", 1);
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.BLOCKED, 200), sim.state());
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals("place+board", sim.bot.rideEvents.getFirst());
        assertEquals(1, sim.bot.rides);
        assertFalse(job.cartLost);
    }

    /** Without a cart chest it is as before: BLOCKED. */
    @Test
    void withNoCartOnTheStartRailNoneCarriedAndNoCartChestTheRideIsBlocked() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = reuseSim(a);
        sim.bot.cartOnRail = false;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("no minecart carried"), sim.engine.status().note());
        assertEquals(0, sim.bot.rides);
        assertFalse(job.cartLost);
    }

    /**
     * Live 2026-11-14: the proxy was restarted during a lost ride's run, so the cart stopped on the track and the booth
     * was empty when the bot came for it. The map's first pass finishes the run first: the viewpoints, the preload lap,
     * on foot to the booth, the wait for the cart, off the brake, back on the start rail, the reset; then the map is
     * printed again.
     */
    @Test
    void aLostRideWhoseCartIsNotOnTheBrakeIsFinishedBeforeTheReprint() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.expectedMinutes = 1;
        cs.ride.reuseCart = true;
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        sim.bot.rideFailsCartInReach = false;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        // the run does not end: no cart on the brake, the layers as printed
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.lostRunOpen;
        }, LONG), sim.state());
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.PENDING, t.status);
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.phase() == Phase.RIDE_RESET && sim.engine.status().note().contains("waiting in the booth");
        }, LONG), sim.state());
        assertEquals(0, sim.bot.cartsBroken);
        assertEquals(2, sim.bot.preloadRides, "the lap before the booth: the pearls under the rail field");
        // no cart within the booth's wait: the lap again (an empty cart stops where the chunks do not tick)
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.bot.preloadRides == 3 && sim.engine.status().note().contains("waiting in the booth");
        }, LONG), sim.state());
        assertEquals(0, sim.bot.cartsBroken);
        // the cart comes in and the muncher ends its run
        clearLayers(sim, cs);
        sim.bot.cartAtBrake = true;
        sim.world.things.add(new WorldView.Thing(4242, "minecart", BRAKE.x() + 0.5, BRAKE.y(), BRAKE.z() + 0.5));
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.bot.cartsBroken > 0) sim.world.things.removeIf(t -> t.id() == 4242);
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertFalse(job.lostRunOpen);
        assertFalse(job.lostCartBack);
        assertEquals(2, sim.bot.rides);
        assertEquals(4, sim.bot.preloadRides);
        assertEquals(List.of("preload-board", "preload", "board", "ride", "preload-board", "preload", "preload-board",
            "preload", "break", "place", "preload-board", "preload", "board", "ride", "break", "place"), sim.bot.rideEvents,
            "the lap, the cart off the brake and back on the start rail, then the second ride as any other");
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
    }

    /** Out of the cart mid-ride with no cart in reach: BLOCKED as before. */
    @Test
    void outOfTheCartMidRideWithoutACartInReachIsBlocked() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.config.site.platforms.getLast().ride.recoverLostRide = false;
        sim.bot.rideFails = "not in the cart (at -100,118,-60, RIDE)";
        sim.bot.rideFailsOutOfCart = true;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        sim.bot.rideFailsCartInReach = false;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("the ride stopped: ride to"), sim.engine.status().note());
        assertEquals(1, sim.bot.launches);
        assertEquals(1, sim.bot.rides);
        assertTrue(job.tiles.getLast().ridePressed);
    }

    @Test
    void withoutAPreloadTheRideIsAsBefore() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.preload = new MapArtConfig.PreloadCfg(); // present but without its rail, stand and button: not configured
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        List<Phase> seen = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            if (!seen.contains(sim.phase())) seen.add(sim.phase());
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertFalse(seen.contains(Phase.RIDE_PRELOAD), seen.toString());
        assertEquals(List.of("place+board", "ride", "break"), sim.bot.rideEvents);
        assertEquals(0, sim.bot.preloadRides);
        assertEquals(1, sim.bot.cartsBroken, "the bot's own cart is taken off the brake as before");
        assertEquals(0, job.tiles.getLast().preloadLaps);
    }

    @Test
    void aMapNotShowingTheFinishedArtIsNotLocked() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.config.site.platforms.getLast().ride.recoverLostRide = false; // as before the recovery: BLOCKED at once
        sim.bot.rideColors = null; // the ride wrote nothing: the dominant pixels still show the fillers
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("pixels off the finished art") && note.contains("not locked"), note);
        assertTrue(sim.maps.locked.isEmpty(), "nothing locked");
        assertEquals(1, sim.bot.rides);
        assertEquals(0, job.badRides);
    }

    /**
     * Live 2026-11-14: a server restart 11 minutes into the ride. The bot came back seated and rode to the brake, but
     * the map showed 4,367 pixels off the finished art (the dominant pixels west of the restart kept their fillers).
     * The cart goes back on the start rail, the reset is waited for and the map is printed again; the second ride
     * ends the job. Nothing is locked from the first ride.
     */
    @Test
    void aRideWhoseMapIsOffIsFollowedByAReprint() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.reuseCart = true; // as live: the platform's own cart goes back on the start rail
        int[] ride = {0};
        sim.bot.rideColors = () -> ride[0]++ == 0 ? SuppressionMaps.afterFill(a, sim.catalog)
            : SuppressionMaps.expected(a, sim.catalog);
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.badRides == 1;
        }, LONG), sim.state());
        assertTrue(sim.maps.locked.isEmpty(), "the first ride's map is not locked");
        assertTrue(job.lostRunOpen, "the cart carried from the brake goes back first");
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.PENDING, t.status);
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertEquals(2, sim.bot.launches, "the second ride is launched like any other");
        assertEquals(List.of("place+board", "ride", "break", "place", "board", "ride", "break", "place"),
            sim.bot.rideEvents, "the cart off the brake and back on the start rail, then the second ride as any other");
        assertEquals(1, job.badRides);
        assertFalse(job.lostRunOpen);
        assertFalse(job.lostCartBack);
        assertEquals(1, sim.maps.locked.size(), "one map locked: the second ride's");
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
    }

    /**
     * Live 2026-11-15: a server restart stalled the top sweeper mid-run. Set right by hand, every machine is home again
     * with part of the top layer still on the platform. Such a run has ended: once the count has been the same for
     * a minute the map is printed again over what is left, not BLOCKED "did not reset" after ride.settleMinutes.
     */
    @Test
    void aLostRunThatEndedShortIsPrintedAgainOverWhatIsLeft() {
        lostRunEndedShort(SuppressionEngineTest.art(), false, false, false);
    }

    /** The same with a stray block on the upper level as well (the stored order: its noobline pass sees it first). */
    @Test
    void aLostRunThatEndedShortWithAStrayOnTheUpperLevelIsPrintedAgain() {
        lostRunEndedShort(SuppressionEngineTest.art(), false, false, true);
    }

    /**
     * The same with {@code build.suppressionMergeNoobline} and {@code build.suppressionMergeLevels} (the print order
     * 2,1,4,3-style: each level's main pass is inspected before its noobline pass, and the level-0 sweep carries the
     * level-1 cells that are gone), and a stray block on the upper level as well. No noobline pass sees the main pass's
     * cells then ({@code mainStandsAlready}): the main passes resume over their own blocks by themselves.
     */
    @Test
    void aLostRunThatEndedShortIsPrintedAgainWithBothMergesOn() {
        lostRunEndedShort(SuppressionEngineTest.art(true, true), true, true, true);
    }

    /** The level merge alone (the stored order): the level-1 noobline pass plans its row alone, its main pass resumes. */
    @Test
    void aLostRunThatEndedShortIsPrintedAgainWithTheLevelMergeOnly() {
        lostRunEndedShort(SuppressionEngineTest.art(), false, true, true);
    }

    /** The noobline merge alone: each level's main pass is inspected first. */
    @Test
    void aLostRunThatEndedShortIsPrintedAgainWithTheNooblineMergeOnly() {
        lostRunEndedShort(SuppressionEngineTest.art(true, true), true, false, true);
    }

    private static void lostRunEndedShort(SuppressionArt a, boolean noobline, boolean levels, boolean upperStray) {
        Sim sim = rideSim(a);
        sim.config.build.suppressionMergeNoobline = noobline;
        sim.config.build.suppressionMergeLevels = levels;
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.reuseCart = true;
        cs.ride.settleMinutes = 30;
        cs.ride.guardConfirmMinutes = 5;
        int top = Math.max(cs.glassY.get(0), cs.glassY.get(1));
        int[] ride = {0}, runs = {0};
        Pos[] walled = {null};
        sim.bot.rideColors = () -> ride[0]++ == 0 ? SuppressionMaps.afterFill(a, sim.catalog)
            : SuppressionMaps.expected(a, sim.catalog);
        sim.bot.afterRide = () -> {
            if (runs[0]++ > 0) {
                clearLayers(sim, cs);
                return;
            }
            // the first run ends short: the bottom layer gone, the top layer only east of column 86
            for (int g : cs.glassY)
                for (int y = g + 1; y <= g + 2; y++)
                    for (int z = cs.minZ - 1; z < cs.minZ + 128; z++)
                        for (int x = cs.minX + (g == top ? 87 : 0); x < cs.minX + 128; x++) sim.world.set(new Pos(x, y, z), null);
            // and the stalled sweeper pushed blocks into cells of the lower level where the art has none (live: four)
            int strays = 0;
            for (int z = cs.minZ + 5; z < cs.minZ + 120 && strays < 3; z += 7)
                for (int x = cs.minX + 80; x > cs.minX + 40; x--) {
                    Pos p = new Pos(x, top + 1, z);
                    BlockSpec at = sim.world.blocks.get(p);
                    if (at != null && !at.isAir()) continue;
                    sim.world.set(p, BlockSpec.of(Sim.WHITE));
                    strays++;
                    break;
                }
            assertEquals(3, strays, "three stray blocks stand on the lower level");
            // one more under a block of the upper level with a block on each side: no face of it can be seen
            // (live: BLOCKED "cannot reach" after four repair passes)
            for (int z = cs.minZ + 2; z < cs.minZ + 126 && walled[0] == null; z++)
                for (int x = cs.minX + 2; x < cs.minX + 85 && walled[0] == null; x++) {
                    Pos p = new Pos(x, top + 1, z);
                    BlockSpec cover = sim.world.blocks.get(p.above());
                    BlockSpec at = sim.world.blocks.get(p);
                    if (cover == null || cover.isAir() || (at != null && !at.isAir())) continue;
                    sim.world.set(p, BlockSpec.of(Sim.WHITE));
                    for (Pos side : List.of(p.offset(1, 0, 0), p.offset(-1, 0, 0), p.offset(0, 0, 1), p.offset(0, 0, -1))) {
                        BlockSpec s = sim.world.blocks.get(side);
                        if (s == null || s.isAir()) sim.world.set(side, BlockSpec.of(Sim.WHITE));
                    }
                    walled[0] = p;
                }
            assertTrue(walled[0] != null, "a stray stands walled in under a block of the upper level");
            // and one on the upper level, in a cell where the art has none and nothing stands below
            if (upperStray) {
                Pos up = new Pos(cs.minX + 60, top + 2, cs.minZ + 40);
                assertTrue(sim.world.block(up).isAir() && sim.world.block(up.offset(0, -1, 0)).isAir());
                sim.world.set(up, BlockSpec.of(Sim.WHITE));
            }
        };
        sim.bot.unbreakable = p -> p.equals(walled[0]) && !sim.world.block(p.above()).isAir();
        java.util.function.IntSupplier left = () -> {
            int n = 0;
            for (int y = top + 1; y <= top + 2; y++)
                for (int z = cs.minZ; z < cs.minZ + 128; z++)
                    for (int x = cs.minX; x < cs.minX + 87; x++) {
                        BlockSpec at = sim.world.blocks.get(new Pos(x, y, z));
                        if (at != null && !at.isAir()) n++;
                    }
            return n;
        };
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.badRides == 1;
        }, LONG), sim.state());
        int leftover = left.getAsInt();
        assertTrue(leftover > 0, "part of the top layer is still there");
        int[] ticks = {0, -1};
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            String note = sim.engine.status().note();
            if (ticks[1] < 0 && note != null && note.startsWith(BuildEngine.RESET_WAIT_NOTE)
                && note.endsWith(" blocks still on the layers")) ticks[1] = ticks[0];
            ticks[0]++;
            return !job.lostRunOpen;
        }, LONG), sim.state());
        assertTrue(ticks[1] >= 0, "it waited with the leftover in its note");
        int waited = ticks[0] - ticks[1];
        int confirm = BuildEngine.LEFTOVER_CONFIRM_MINUTES * 1200;
        assertTrue(waited >= confirm, "not before the confirm time: " + waited + " ticks");
        assertTrue(waited <= confirm + 2 * BuildEngine.SETTLE_POLL_TICKS + 200, "at once after it, not after the 5"
            + " minutes of ride.guardConfirmMinutes or the 30 of ride.settleMinutes: " + waited + " ticks");
        assertEquals(leftover, left.getAsInt(), "nothing of what was left is taken away");
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, sim.bot.rides);
        assertEquals(1, sim.maps.locked.size(), "one map locked: the second ride's");
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
    }

    /**
     * Live 2026-11-15: a lost run's recovery found the bot seated in the cart by the brake (a run ridden to its end with
     * no map in the hand). It gets out in the booth, takes the cart off the brake and puts it back on the start rail;
     * then the reset and the print as after any lost run.
     */
    @Test
    void aLostRunWithTheBotSeatedInTheCartByTheBrakeGetsOutFirst() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.reuseCart = true;
        Job job = SuppressionEngineTest.addJob(sim, a);
        job.lostRunOpen = true;
        sim.inv.remove("minecraft:minecart", 1); // the platform's cart is the one the bot sits in
        sim.world.moveTo(BRAKE);
        sim.world.vehicle = new WorldView.Thing(4242, "minecart", BRAKE.x() + 0.5, BRAKE.y(), BRAKE.z() + 0.5);
        sim.bot.cartAtBrake = true;
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("break", "place", "board", "ride", "break", "place"), sim.bot.rideEvents,
            "out of the cart, the cart off the brake and back on the start rail, then the ride as any other");
        assertFalse(job.lostRunOpen);
        assertFalse(job.lostCartBack);
        assertEquals(1, sim.bot.rides);
        for (Job.TileState ts : job.tiles) assertEquals(TileStatus.STORED, ts.status);
    }

    /** 2b2t's restart cycle: a ride that would still be under way at the next restart is not started. */
    @Test
    void aRideIsInsideTheScheduledRestartOnlyWhenItWouldStillBeUnderWayThen() {
        long last = 1_760_000_000_000L, min = 60_000L, ride = 90 * min;
        long due = last + 4627 * min;
        assertFalse(BuildEngine.restartInsideRide(due - 91 * min, last, 4627, ride), "the ride ends before the restart");
        assertTrue(BuildEngine.restartInsideRide(due - 90 * min, last, 4627, ride));
        assertTrue(BuildEngine.restartInsideRide(due - 1, last, 4627, ride));
        assertTrue(BuildEngine.restartInsideRide(due + 29 * min, last, 4627, ride), "not seen yet: still waited for");
        assertFalse(BuildEngine.restartInsideRide(due + 31 * min, last, 4627, ride), "none seen: the schedule is off");
        assertFalse(BuildEngine.restartInsideRide(due - min, last, 0, ride), "switched off");
        assertFalse(BuildEngine.restartInsideRide(due - min, 0, 4627, ride), "no restart on record");
    }

    /**
     * Live 2026-11-14: 2b2t restarted 11 minutes into a ride (the map lost, the muncher stalled). With
     * {@code site.restartEveryMinutes} the filled map waits at the boarding while the scheduled restart is due inside
     * the ride, and the ride starts once that restart is on record.
     */
    @Test
    void theRideWaitsForTheScheduledRestartAndStartsAfterIt() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.config.site.restartEveryMinutes = 4627;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            // the last restart on record puts the next one 20 minutes after the fill
            if (sim.phase() == Phase.FILL_MAP)
                sim.engine.lastServerRestartAt = sim.clock.millis() - 4627 * 60_000L + 20 * 60_000L;
            return sim.phase() == Phase.RIDE_BOARD && sim.engine.status().note().startsWith(BuildEngine.RESTART_EXPECTED_NOTE);
        }, LONG), sim.state());
        sim.run(2_400);
        assertEquals(Phase.RIDE_BOARD, sim.phase(), "still waiting two minutes later");
        assertTrue(sim.bot.rideEvents.isEmpty(), "nothing boarded, nothing pressed: " + sim.bot.rideEvents);
        assertTrue(sim.notifier.has("detail", "Ride waits for the restart"), sim.notifier.toString());
        // the restart comes: announced, the bot moved to the queue, back in game
        sim.engine.onServerMessage("Server restarting in 1 minute...");
        sim.run(20);
        sim.engine.onRequeued();
        sim.run(20);
        assertEquals(sim.clock.millis(), sim.engine.lastServerRestartAt, 2_000, "the restart is on record");
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.rides);
    }

    /** A lane end by the start rail's row: the corner rail under a closed iron trapdoor, the hold east of it, the lane south. */
    static final Pos LANE_CORNER = new Pos(RAIL.x() - 15, RAIL.y() - 2, RAIL.z());
    static final Pos LANE_HOLD = LANE_CORNER.offset(1, 0, 0);
    static final Pos LANE_RAIL = LANE_CORNER.offset(0, 2, 3);

    static void laneEnd(Sim sim) {
        laneEnd(sim, LANE_CORNER);
    }

    /** A lane end whose corner rail is {@code corner}: its hold east of it, its lane to the south. */
    static void laneEnd(Sim sim, Pos corner) {
        sim.world.set(corner, BlockSpec.parse("minecraft:rail[shape=south_east]"));
        sim.world.set(corner.above(), BlockSpec.parse("minecraft:iron_trapdoor[facing=east,half=bottom,open=false]"));
        sim.world.set(corner.offset(1, 0, 0), BlockSpec.parse("minecraft:powered_rail[powered=true,shape=ascending_east]"));
        sim.world.set(corner.offset(1, -1, 0), BlockSpec.of("minecraft:redstone_block"));
        sim.world.set(corner.offset(0, 0, 1), BlockSpec.parse("minecraft:powered_rail[powered=true,shape=ascending_south]"));
        sim.world.set(corner.offset(0, 1, 2), BlockSpec.parse("minecraft:powered_rail[powered=true,shape=ascending_south]"));
        sim.world.set(corner.offset(0, 2, 3), BlockSpec.parse("minecraft:powered_rail[powered=true,shape=north_south]"));
    }

    @Test
    void aLaneEndIsReadFromTheBlocksAroundTheCartStandingInItsHold() {
        Sim sim = new Sim();
        laneEnd(sim);
        BuildEngine.LaneEnd e = BuildEngine.laneEnd(sim.world, LANE_HOLD.x() + 0.49, LANE_HOLD.y() + 0.55, LANE_HOLD.z() + 0.5);
        assertEquals(new BuildEngine.LaneEnd(LANE_HOLD, LANE_CORNER, LANE_RAIL, 0f), e);
        // on the lane itself, at the brake, with the trapdoor open: no hold
        assertEquals(null, BuildEngine.laneEnd(sim.world, LANE_RAIL.x() + 0.5, LANE_RAIL.y() + 0.1, LANE_RAIL.z() + 0.5));
        assertEquals(null, BuildEngine.laneEnd(sim.world, BRAKE.x() + 0.5, BRAKE.y(), BRAKE.z() + 0.5));
        sim.world.set(LANE_CORNER.above(), BlockSpec.parse("minecraft:iron_trapdoor[facing=east,half=bottom,open=true]"));
        assertEquals(null, BuildEngine.laneEnd(sim.world, LANE_HOLD.x() + 0.49, LANE_HOLD.y() + 0.55, LANE_HOLD.z() + 0.5));
        // a far end's lane goes north
        Pos c = new Pos(10, 60, 10);
        sim.world.set(c, BlockSpec.parse("minecraft:rail[shape=north_east]"));
        sim.world.set(c.above(), BlockSpec.parse("minecraft:iron_trapdoor[open=false]"));
        sim.world.set(c.offset(1, 0, 0), BlockSpec.parse("minecraft:powered_rail[shape=ascending_east]"));
        sim.world.set(c.offset(0, 0, -1), BlockSpec.parse("minecraft:powered_rail[shape=ascending_north]"));
        sim.world.set(c.offset(0, 1, -2), BlockSpec.parse("minecraft:powered_rail[shape=ascending_north]"));
        sim.world.set(c.offset(0, 2, -3), BlockSpec.parse("minecraft:powered_rail[shape=north_south]"));
        assertEquals(new BuildEngine.LaneEnd(c.offset(1, 0, 0), c, c.offset(0, 2, -3), 180f),
            BuildEngine.laneEnd(sim.world, c.x() + 1.49, c.y() + 0.5, c.z() + 0.5));
    }

    /**
     * Owner 2026-11-15: a server restart announced during the ride. Once the cart stands in a lane-end hold on the
     * walkway's side the map leaves the hand, the bot gets out, breaks the cart and stands on the hold's own cell with
     * the map until the restart. Back in game it puts the map away, walks the viewpoints and rides the preload lap, goes
     * back to the hold, puts the cart on the next lane's first level rail, gets in and pushes off with the map in hand.
     * The launch button is pressed once; the ride ends at the brake as any other.
     */
    @Test
    void aRestartAnnouncedMidRidePausesTheRideAtALaneEndAndItGoesOnAfterTheRestart() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        MapArtConfig.PlatformCfg cs = sim.config.site.platforms.getLast();
        cs.ride.restartPause = true;
        laneEnd(sim);
        sim.bot.rideHoldAt = LANE_HOLD;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        boolean[] warned = {false};
        Job.TileState last = job.tiles.getLast();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.phase() == Phase.RIDE && !warned[0]) {
                warned[0] = true;
                sim.engine.onServerMessage("Server restarting in 5 minutes...");
            }
            return last.ridePauseStage == BuildEngine.RP_STAND && !sim.bot.exactStands.isEmpty();
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "board", "ride", "stop", "break", "stand"), sim.bot.rideEvents);
        assertEquals(LANE_HOLD, last.ridePause);
        assertEquals(LANE_RAIL, last.ridePauseRail);
        assertEquals(LANE_HOLD, sim.bot.exactStands.getLast(), "on the hold's own cell, the seat's block column");
        assertEquals(1, sim.inv.count("minecraft:minecart"), "the cart is in the inventory");
        assertTrue(sim.maps.held != null, "the map is in the hand on the stand");
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.RIDE_PAUSE_NOTE), sim.engine.status().note());
        assertTrue(sim.notifier.has("info", "Ride paused for the restart"), sim.notifier.toString());
        // it stands there until the restart
        sim.run(2_400);
        assertEquals(Phase.RIDE, sim.phase());
        assertEquals(BuildEngine.RP_STAND, last.ridePauseStage);
        assertEquals(List.of("preload-board", "preload", "board", "ride", "stop", "break", "stand"), sim.bot.rideEvents);
        // the restart: the connection goes, the bot is back in game where it stood
        sim.engine.onServerMessage("Server restarting in 10 seconds...");
        sim.run(20);
        sim.engine.onRequeued();
        sim.run(5);
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.bot.rideEvents.contains("ride after a push");
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "board", "ride", "stop", "break", "stand", "preload-board",
            "preload", "place+board", "ride after a push"), sim.bot.rideEvents, "the lap again, then the cart back on the lane");
        assertEquals(List.of(), sim.bot.footRoutes, "a hold in the start rail's row: the walkway is beside it, no way round");
        assertEquals(null, last.ridePause);
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.launches, "the launch button once");
        assertEquals(2, sim.bot.preloadRides, "the lap before the ride and again after the restart");
        assertEquals(1, sim.maps.locked.size());
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
    }

    /** The announced restart does not come: after its grace the bot gets back into the cart, with no lap (the same connection). */
    @Test
    void aPausedRideWhoseRestartDoesNotComeGoesOnWithoutTheLap() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        sim.config.site.platforms.getLast().ride.restartPause = true;
        laneEnd(sim);
        sim.bot.rideHoldAt = LANE_HOLD;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        boolean[] warned = {false};
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.phase() == Phase.RIDE && !warned[0]) {
                warned[0] = true;
                sim.engine.onServerMessage("Server restarting in 1 minute...");
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(List.of("preload-board", "preload", "board", "ride", "stop", "break", "stand", "place+board",
            "ride after a push", "break", "place"), sim.bot.rideEvents);
        assertEquals(1, sim.bot.preloadRides);
        assertEquals(1, sim.bot.launches);
    }

    /**
     * A hold with no opened cell beside it (no access box: the far row before its walkway was opened) is not used: the
     * bot stays in the cart. With an access box over the cell beside it, it is used like any other.
     */
    @Test
    void aRideIsPausedOnlyAtAHoldWithAnOpenedWalkwayBesideIt() {
        SuppressionArt a = SuppressionEngineTest.art();
        Pos far = new Pos(RAIL.x() - 15, RAIL.y() - 2, RAIL.z() + 40); // outside the ride's access box
        for (boolean opened : new boolean[]{false, true}) {
            Sim sim = preloadSim(a);
            MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
            r.restartPause = true;
            if (opened) r.access.add(box(far.offset(-20, 0, 1), far.offset(20, 2, 2), "ride: far-row strip"));
            laneEnd(sim, far);
            sim.bot.rideHoldAt = far.offset(1, 0, 0);
            Job job = SuppressionEngineTest.addJob(sim, a);
            sim.start();
            boolean[] warned = {false};
            assertTrue(sim.runUntil(() -> {
                assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
                if (sim.phase() == Phase.RIDE && !warned[0]) {
                    warned[0] = true;
                    sim.engine.onServerMessage("Server restarting in 1 minute...");
                }
                return job.status == JobStatus.DONE;
            }, LONG), sim.state());
            assertEquals(opened ? 1 : 0, sim.bot.rideStops, opened ? "the far row's strip is opened" : "no way from there");
        }
    }

    /**
     * A hold off the start rail's row (local run 2026-11-15, the far row: asked for a viewpoint from its strip the
     * pathfinder headed for the strip's end nearest it and stalled, no viewpoint was reached). After the restart a foot
     * route leads round to the booth before the viewpoints and the lap, and another back to the hold before the cart is
     * put on the next lane.
     */
    @Test
    void aRidePausedOffTheStartRailsRowTakesFootRoutesToTheBoothAndBackToItsHold() {
        SuppressionArt a = SuppressionEngineTest.art();
        Pos far = new Pos(RAIL.x() - 15, RAIL.y() - 2, RAIL.z() + 40);
        Pos hold = far.offset(1, 0, 0);
        Sim sim = preloadSim(a);
        MapArtConfig.RideCfg r = sim.config.site.platforms.getLast().ride;
        r.restartPause = true;
        r.access.add(box(far.offset(-20, 0, 1), far.offset(20, 2, 2), "ride: far-row strip"));
        laneEnd(sim, far);
        sim.bot.rideHoldAt = hold;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        boolean[] warned = {false};
        Job.TileState last = job.tiles.getLast();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.phase() == Phase.RIDE && !warned[0]) {
                warned[0] = true;
                sim.engine.onServerMessage("Server restarting in 5 minutes...");
            }
            return last.ridePauseStage == BuildEngine.RP_STAND && !sim.bot.exactStands.isEmpty();
        }, LONG), sim.state());
        assertEquals(hold, last.ridePause);
        sim.bot.footRoutes.clear();
        sim.engine.onServerMessage("Server restarting in 10 seconds...");
        sim.run(20);
        sim.engine.onRequeued();
        sim.run(5);
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return sim.bot.rideEvents.contains("ride after a push");
        }, LONG), sim.state());
        // (the walk to the booth is asked again when bot ticks start anew in the middle of the reload, as here)
        List<Pos> routes = sim.bot.footRoutes;
        assertEquals(BRAKE, routes.getFirst(), "round to the booth first: " + routes);
        assertEquals(hold, routes.getLast(), "round back to the hold last: " + routes);
        assertTrue(routes.subList(0, routes.size() - 1).stream().allMatch(BRAKE::equals), routes.toString());
        assertTrue(BuildEngine.inRailField(r, hold, hold), "the hold lies in the field");
        assertTrue(BuildEngine.inRailField(r, hold, hold.offset(0, -3, -30)), "so does the floor under the field");
        assertFalse(BuildEngine.inRailField(r, hold, hold.offset(0, -5, -30)), "the lap's track under it does not");
        assertFalse(BuildEngine.inRailField(r, hold, BRAKE), "the booth is out of it");
        assertFalse(BuildEngine.inRailField(r, hold, STAND), "so is the start stand");
        assertFalse(BuildEngine.inRailField(r, hold, RAIL.offset(40, -3, 40)), "and the art");
        assertEquals(2, sim.bot.preloadRides, "the lap again after the restart");
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.bot.launches, "the launch button once");
    }

    /** Without the switch, at a far end's hold, or with no restart announced, the ride goes on as before. */
    @Test
    void aRideIsNotPausedWithoutTheSwitchOrAnAnnouncedRestart() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = preloadSim(a);
        laneEnd(sim);
        sim.bot.rideHoldAt = LANE_HOLD;
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        boolean[] warned = {false};
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            if (sim.phase() == Phase.RIDE && !warned[0]) {
                warned[0] = true;
                sim.engine.onServerMessage("Server restarting in 5 minutes...");
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(0, sim.bot.rideStops, "the switch is off");
        // the switch on, no restart announced
        Sim quiet = preloadSim(a);
        quiet.config.site.platforms.getLast().ride.restartPause = true;
        laneEnd(quiet);
        quiet.bot.rideHoldAt = LANE_HOLD;
        Job second = SuppressionEngineTest.addJob(quiet, a);
        quiet.start();
        assertTrue(quiet.runUntil(() -> {
            assertTrue(quiet.phase() != Phase.BLOCKED, quiet.state());
            return second.status == JobStatus.DONE;
        }, LONG), quiet.state());
        assertEquals(0, quiet.bot.rideStops, "no restart announced");
    }

    /** After {@code BAD_RIDE_REPRINTS} reprints a map that is still off is BLOCKED as before: no endless printing. */
    @Test
    void ridesWhoseMapStaysOffAreBlockedAfterTheReprints() {
        SuppressionArt a = SuppressionEngineTest.art();
        Sim sim = rideSim(a);
        sim.config.site.platforms.getLast().ride.reuseCart = true;
        sim.bot.rideColors = () -> SuppressionMaps.afterFill(a, sim.catalog); // every ride leaves the fillers
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 4 * LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("pixels off the finished art") && note.contains("not locked"), note);
        assertEquals(BuildEngine.BAD_RIDE_REPRINTS, job.badRides);
        assertEquals(BuildEngine.BAD_RIDE_REPRINTS + 1, sim.bot.rides);
        assertTrue(sim.maps.locked.isEmpty(), "nothing locked");
    }

    /** The expected ride for the status: the platform's last completed ride when it is a sane one, else the config's. */
    @Test
    void theExpectedRideIsTheLastCompletedOneOnThePlatformWithinReason() {
        long t0 = 1_760_000_000_000L;
        Job first = csJob("cs", t0, t0 + 4_530_000L);                    // 75.5 min
        Job second = csJob("cs", t0 + 10_000_000L, t0 + 14_500_000L);    // 75.0 min, the newer one
        Job other = csJob("cs2", t0 + 20_000_000L, t0 + 23_000_000L);    // another platform
        Job local = csJob("cs", t0 + 30_000_000L, t0 + 30_450_000L);     // 7.5 min: a 10x test ride, not a measure
        Job open = csJob("cs", t0 + 40_000_000L, 0);                      // launched, never reached the brake
        Job flat = new Job();
        flat.tiles.add(new Job.TileState());
        assertEquals(75 * 60, BuildEngine.expectedRideSeconds(List.of(), "cs", 75));
        assertEquals(4_530, BuildEngine.expectedRideSeconds(List.of(first, flat), "cs", 75));
        assertEquals(4_500, BuildEngine.expectedRideSeconds(List.of(first, second, other, local, open, flat), "cs", 75));
        assertEquals(4_500, BuildEngine.expectedRideSeconds(List.of(second, first), "cs", 75), "the newest, whatever the order");
        assertEquals(3_000, BuildEngine.expectedRideSeconds(List.of(other), "cs2", 60));
        assertEquals(180 * 60, BuildEngine.expectedRideSeconds(List.of(first), "cs", 180), "too short for a 180 min ride: the config value");
    }

    private static Job csJob(String platform, long started, long ended) {
        Job j = new Job();
        j.suppression = new ArrayList<>(List.of(new Job.SuppressionPass(SuppressionArt.TOP, 0, false)));
        Job.TileState t = new Job.TileState();
        t.platformId = platform;
        t.ridePressed = true;
        t.rideStartedAt = started;
        t.rideEndedAt = ended;
        j.tiles.add(t);
        return j;
    }
}
