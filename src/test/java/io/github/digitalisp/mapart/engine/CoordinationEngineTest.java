package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.coord.Claim;
import io.github.digitalisp.mapart.coord.TileProgress;
import io.github.digitalisp.mapart.coord.TileUnitSpec;
import io.github.digitalisp.mapart.coord.Unit;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine behind a scripted coordination ({@link FakeCoordination}): it builds only the tiles it holds a unit of, claims the
 * platform, chests and stations, reports progress, stops within ticks when the unit is taken back, and a second
 * account resumes the tile from the world.
 */
class CoordinationEngineTest {
    private static final int LONG = 60_000;

    private static Sim seam(FakeCoordination[] out) {
        Sim sim = new Sim();
        FakeCoordination coord = new FakeCoordination(sim.store);
        coord.now = sim.clock::millis;
        out[0] = coord;
        sim.newEngine(sim.store, coord);
        return sim;
    }

    private static boolean stopped(Sim sim) {
        return sim.phase() == Phase.IDLE && (sim.bot.lastTask == null || sim.bot.lastTask.isFinished());
    }

    @Test
    void grantedUnitIsBuiltReportedAndDone() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0101", "Seam", 1, 1, List.of("moss"), (x, z) -> art);
        sim.bot.placementsPerTick = 4; // a load takes longer than the 5 s cadence
        coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(1, sim.output().maps.size(), "one map stored");
        assertTrue(coord.reports.contains("done " + job.id + "/0,0"), coord.reports.toString());
        assertNotNull(coord.doneWith);
        assertTrue(coord.doneWith.stored());
        assertEquals(art.nonEmptyCount(), coord.doneWith.total());
        // progress: every phase change, and at most every 5 s while BUILD places cells
        assertTrue(coord.progress.stream().anyMatch(p -> p.phase().equals("BUILD")), coord.reports.toString());
        assertTrue(coord.progress.stream().anyMatch(p -> p.phase().equals("VERIFY") && p.placed() == p.total()),
            coord.reports.toString());
        int buildReports = 0;
        for (int i = 1; i < coord.progress.size(); i++) {
            TileProgress a = coord.progress.get(i - 1), b = coord.progress.get(i);
            if (!a.phase().equals("BUILD") || !b.phase().equals("BUILD")) continue;
            buildReports++;
            assertTrue(coord.progressAt.get(i) - coord.progressAt.get(i - 1) >= 5_000, "cadence: " + coord.progressAt);
            assertTrue(b.placed() > a.placed(), "a timed report carries new cells");
        }
        assertTrue(buildReports > 0, "timed reports during BUILD: " + coord.reports);
        // claims: the platform for the tile, every menu while open, all given back
        assertFalse(coord.claims("platform:moss").isEmpty());
        assertFalse(coord.claims("chest:").isEmpty());
        assertEquals(1, coord.claims("station:cartography").size(), coord.claims.toString());
        assertEquals(1, coord.claims("station:anvil").size(), coord.claims.toString());
        assertTrue(coord.held().isEmpty(), "every claim released: " + coord.held());
    }

    @Test
    void onlyGrantedTilesAreBuilt() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        TileArt left = Sim.single(Sim.WHITE, 4);
        TileArt right = Sim.single(Sim.RED, 4);
        Job job = sim.addJob("j0102", "Wide", 2, 1, List.of("moss"), (x, z) -> x == 0 ? left : right);
        coord.grant(job, 1, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(1).status == TileStatus.STORED, LONG), sim.state());
        sim.run(2_000);

        assertEquals(TileStatus.PENDING, job.tiles.get(0).status, "tile 0,0 is another bot's");
        assertEquals(1, sim.output().maps.size());
        assertEquals("Wide 1,0", sim.output().maps.getFirst().name());
        assertTrue(job.status != JobStatus.DONE, "the job is not this bot's to finish");
        assertTrue(sim.phase() == Phase.IDLE || sim.phase() == Phase.SELECT, sim.state());
    }

    @Test
    void revokeMidBuildStopsWithinTicksAndLeavesTheTile() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0103", "Revoked", 1, 1, List.of("moss"), (x, z) -> art);
        Unit u = coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 300, LONG), sim.state());

        coord.revoke(u);
        int ticks = 0;
        while (!stopped(sim) && ticks < 20) {
            sim.tick();
            ticks++;
        }
        assertTrue(stopped(sim), "stopped: " + sim.state());
        assertTrue(ticks <= 2, "stopped within " + ticks + " ticks");
        int placed = sim.bot.placed;
        int onPlatform = sim.artBlocks(sim.moss);
        sim.run(2_000);
        assertEquals(placed, sim.bot.placed, "nothing placed after the revoke");
        assertEquals(onPlatform, sim.artBlocks(sim.moss), "the partial build stays for the next holder (no nuke)");
        assertTrue(onPlatform > 0);
        assertTrue(coord.held().isEmpty(), "claims released: " + coord.held());
        assertTrue(coord.reports.stream().noneMatch(r -> r.startsWith("done") || r.startsWith("release")),
            "a revoked unit is not reported: " + coord.reports);
        assertTrue(sim.modules.isEnabled("AutoEat") && sim.modules.isEnabled("KillAura"), "parked idle, modules back on");
    }

    @Test
    void leaseLostWithAMenuOpenGivesTheChestBack() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        sim.bot.containerTicks = 5; // a menu open for several ticks
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0104", "Lease", 1, 1, List.of("moss"), (x, z) -> art);
        Unit u = coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RESTOCK
            && coord.claims("chest:").stream().anyMatch(k -> k.state == Claim.State.HELD), LONG), sim.state());

        coord.loseLease(u);
        int ticks = 0;
        while (!stopped(sim) && ticks < 20) {
            sim.tick();
            ticks++;
        }
        assertTrue(stopped(sim), "stopped: " + sim.state());
        assertTrue(ticks <= 2, "stopped within " + ticks + " ticks");
        assertTrue(sim.bot.lastTask == null || sim.bot.lastTask.failure() != null || sim.bot.lastTask.isFinished());
        assertTrue(coord.held().isEmpty(), "chest and platform claims released: " + coord.held());
        int withdrawals = (int) sim.bot.count("withdraw");
        sim.run(1_000);
        assertEquals(withdrawals, (int) sim.bot.count("withdraw"), "no more chest work");
    }

    @Test
    void platformClaimWaitsThenBuildsAndALostClaimGivesTheUnitBack() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        coord.platformStart = Claim.State.WAITING; // another bot builds on moss
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0105", "Waits", 1, 1, List.of("moss"), (x, z) -> art);
        coord.grant(job, 0, 0);
        sim.start();
        sim.run(600);
        assertEquals(0, sim.bot.placed, "nothing built while the platform is another bot's");
        assertEquals(Phase.INSPECT, sim.phase(), sim.state());
        assertTrue(sim.engine.status().note().contains("waiting for platform moss"), sim.state());

        FakeCoordination.FakeClaim platform = coord.claims("platform:moss").getLast();
        platform.state = Claim.State.HELD; // the other bot is done
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 300, LONG), sim.state());

        platform.state = Claim.State.LOST; // e.g. the coordinator restarted and someone else holds it now
        int ticks = 0;
        while (!stopped(sim) && ticks < 20) {
            sim.tick();
            ticks++;
        }
        assertTrue(stopped(sim), "stopped: " + sim.state());
        assertTrue(ticks <= 2, "stopped within " + ticks + " ticks");
        assertTrue(coord.reports.contains("release " + job.id + "/0,0 claim_lost"), coord.reports.toString());
        int placed = sim.bot.placed;
        sim.run(1_000);
        assertEquals(placed, sim.bot.placed, "the unit went back: nothing more built");
    }

    /** What the second account did with the first one's half-built tile. */
    private record HandOver(Sim sim, SimFakes.FakeStore store2, FakeCoordination second, TileArt art, int onPlatform) {
        boolean nukedBeforeStore() {
            int store = store2.phaseLog.indexOf("STORE");
            assertTrue(store > 0, store2.phaseLog.toString());
            return store2.phaseLog.subList(0, store).contains("NUKE");
        }
    }

    /**
     * The first account builds part of the tile and loses it (revoked mid-BUILD); a second account, with its own queue
     * (the unit's spec as a fresh PENDING job), no checkpoint and its own coordination, is granted the unit
     * ({@code attempt}, with or without the first one's progress) and finishes it on the same world.
     */
    private static HandOver handOver(String id, int attempt, boolean withProgress) {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination first = c[0];
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob(id, "Handed", 1, 1, List.of("moss"), (x, z) -> art);
        for (int i = 0; i < 2; i++) { // stock for a second build from clear (the nuke's case)
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 104), Map.of(Sim.WHITE, 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 106), Map.of(Sim.RED, 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 108), Map.of(Sim.BLACK, 1728));
        }
        sim.bot.placementsPerTick = 4; // timed reports while the lanes run
        Unit u = first.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 1_500, LONG), sim.state());
        first.revoke(u);
        sim.run(5);
        assertTrue(stopped(sim), sim.state());
        TileProgress handed = first.lastProgress();
        assertNotNull(handed);
        assertTrue(handed.started() && handed.placed() > 0, handed.toString());
        int onPlatform = sim.artBlocks(sim.moss);
        assertTrue(onPlatform > 1_000, "half built: " + onPlatform);

        TileUnitSpec spec = TileUnitSpec.of(job, job.tiles.getFirst(), art);
        SimFakes.FakeStore store2 = new SimFakes.FakeStore();
        Job j2 = spec.toJob();
        assertEquals(TileStatus.PENDING, j2.tiles.getFirst().status, "the successor's tile is not ACTIVE");
        store2.jobs.add(j2);
        store2.tiles.put(j2.id + ":" + new TileCoord(0, 0), spec.art());
        assertNull(store2.checkpoint.jobId, "the successor has no checkpoint");
        FakeCoordination second = new FakeCoordination(store2);
        second.grant(j2, 0, 0, attempt, withProgress ? handed : null, spec);
        sim.newEngine(store2, second);
        sim.start();
        assertTrue(sim.runUntil(() -> j2.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.output().maps.size());
        assertEquals("Handed", sim.output().maps.getFirst().name());
        assertTrue(second.reports.contains("done " + j2.id + "/0,0"), second.reports.toString());
        return new HandOver(sim, store2, second, art, onPlatform);
    }

    private static void assertResumed(HandOver h) {
        assertFalse(h.nukedBeforeStore(), "the partial build was resumed, not nuked: " + h.store2().phaseLog);
        for (var e : h.sim().bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed once: " + e.getKey());
        assertEquals(h.art().nonEmptyCount(), h.sim().bot.placed, "the successor placed only what was missing");
        assertTrue(h.sim().notifier.has("detail", "Resuming Handed"), h.sim().notifier.toString());
    }

    @Test
    void secondAccountResumesFromTheWorld() {
        assertResumed(handOver("j0106", 2, true));
    }

    @Test
    void aRetriedUnitResumesWithoutHandedOverProgress() {
        assertResumed(handOver("j0109", 2, false)); // attempt 2: someone worked on it
    }

    @Test
    void handedOverProgressAloneMakesAResume() {
        assertResumed(handOver("j0110", 1, true)); // e.g. released, not failed: the progress shows the start
    }

    /**
     * A first grant with nothing handed over and no marker, on a partial build whose every block is the tile's own in
     * its own cell: resumed, never nuked . A first grant that finds a block not the
     * tile's is foreign as always.
     */
    @Test
    void aFirstGrantResumesABuildWithNoBlockButTheTiles() {
        HandOver h = handOver("j0111", 1, false);
        assertFalse(h.nukedBeforeStore(), "every block there is the tile's: resumed: " + h.store2().phaseLog);
        assertEquals(h.art().nonEmptyCount(), h.sim().bot.placed, "the successor placed only what was missing");
    }

    @Test
    void storedUnitIsNotBuiltOrStoredAgain() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination first = c[0];
        TileArt art = Sim.stripes(8, 2);
        Job job = sim.addJob("j0107", "Kept", 1, 1, List.of("moss"), (x, z) -> art);
        Unit u = first.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.getFirst().status == TileStatus.STORED, LONG), sim.state());
        first.loseLease(u);
        sim.run(5);
        assertTrue(stopped(sim), sim.state());
        TileProgress handed = first.lastProgress();
        assertNotNull(handed);
        assertTrue(handed.stored(), "the store was reported: " + first.reports);
        assertEquals(1, sim.output().maps.size());

        TileUnitSpec spec = TileUnitSpec.of(job, job.tiles.getFirst(), art);
        SimFakes.FakeStore store2 = new SimFakes.FakeStore();
        Job j2 = spec.toJob();
        store2.jobs.add(j2);
        store2.tiles.put(j2.id + ":" + new TileCoord(0, 0), spec.art());
        FakeCoordination second = new FakeCoordination(store2);
        second.grant(j2, 0, 0, 2, handed, spec);
        sim.newEngine(store2, second);
        sim.start();
        assertTrue(sim.runUntil(() -> j2.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(1, sim.output().maps.size(), "no second map");
        for (String p : List.of("BUILD", "FILL_MAP", "LOCK", "RENAME", "STORE"))
            assertFalse(store2.phaseLog.contains(p), p + " again: " + store2.phaseLog);
        assertEquals(0, sim.artBlocks(sim.moss), "the successor cleared the platform");
        assertTrue(second.reports.contains("done " + j2.id + "/0,0"), second.reports.toString());
    }

    private static Pos feet(Sim sim) {
        return new Pos((int) Math.floor(sim.bot.world.x), (int) Math.floor(sim.bot.world.y),
            (int) Math.floor(sim.bot.world.z));
    }

    /**
     * Gap 1 of the seam (prompt 2): after an abandon the bot does not park on the art layer. It walks to an exit
     * outside every clear sweep (here the centre structure, away from the clear button), placing and breaking nothing.
     */
    @Test
    void anAbandonOnTheArtLayerStepsOffIt() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        Pos exit = new Pos(-100, 65, 0); // west of the moss tile, beyond the sweep margin
        sim.moss.center = exit;
        sim.moss.clear.getFirst().standAt = new Pos(-100, 65, 40); // the clear stand: far from the centre
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0112", "StepOff", 1, 1, List.of("moss"), (x, z) -> art);
        Unit u = coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 300, LONG), sim.state());
        assertTrue(io.github.digitalisp.mapart.bot.impl.StandSpots.onArtLayer(sim.moss.geometry(), feet(sim)),
            "on the art layer: " + feet(sim));
        int placed = sim.bot.placed;
        int onPlatform = sim.artBlocks(sim.moss);
        long broken = sim.bot.count("break");

        coord.revoke(u);
        sim.run(40);
        assertEquals(Phase.IDLE, sim.phase(), sim.state());
        assertEquals(exit.x(), feet(sim).x(), "walked to the exit: " + sim.state());
        assertEquals(exit.z(), feet(sim).z(), "walked to the exit: " + sim.state());
        assertEquals(placed, sim.bot.placed, "nothing placed after the revoke");
        assertEquals(onPlatform, sim.artBlocks(sim.moss), "the partial build stays as it is");
        assertEquals(broken, sim.bot.count("break"), "nothing broken on the way off");
        assertTrue(coord.held().isEmpty(), "claims released: " + coord.held());
        sim.run(400);
        assertEquals(exit.x(), feet(sim).x(), "stays off the art layer while idle");
    }

    /**
     * the owner 2026-10-25: a bot without the tile's tail never walks up to the clear button. An abandon whose only exit off
     * the art layer is a clear stand spot (the centre on the art layer) leaves the bot standing where it is.
     */
    @Test
    void anAbandonNeverWalksToTheClearStand() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        Pos clearStand = new Pos(-100, 65, 0); // west of the moss tile, beyond the sweep margin
        sim.moss.clear.getFirst().standAt = clearStand;
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0113", "NoClearStand", 1, 1, List.of("moss"), (x, z) -> art);
        Unit u = coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 300, LONG), sim.state());
        long broken = sim.bot.count("break");
        long presses = sim.bot.count("pressButton");

        coord.revoke(u);
        sim.run(10);
        Pos at = feet(sim);
        sim.run(400);
        assertEquals(Phase.IDLE, sim.phase(), sim.state());
        assertEquals(at, feet(sim), "stood still after the abandon: " + sim.state());
        assertTrue(Math.abs(feet(sim).x() - clearStand.x()) > BuildEngine.CLEAR_KEEP_AWAY, "not at the clear stand");
        assertTrue(sim.bot.history.stream().noneMatch(h -> h.startsWith("walkTo " + clearStand)), "no walk to the clear stand");
        assertEquals(broken, sim.bot.count("break"), "nothing broken");
        assertEquals(presses, sim.bot.count("pressButton"), "nothing pressed");
        assertTrue(coord.held().isEmpty(), "claims released: " + coord.held());
    }

    /** Gap 2 of the seam (prompt 2): a chest claim lost while its menu is open closes the menu at once. */
    @Test
    void aLostChestClaimClosesItsMenuAtOnce() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        sim.bot.containerTicks = 40; // a menu open for two seconds
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0113", "MenuLost", 1, 1, List.of("moss"), (x, z) -> art);
        coord.grant(job, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> coord.claims("chest:").stream().anyMatch(k -> k.state == Claim.State.HELD), LONG),
            sim.state());
        sim.tick();
        assertTrue(sim.bot.menuDrops.isEmpty());
        FakeCoordination.FakeClaim open = coord.claims("chest:").stream().filter(k -> k.state == Claim.State.HELD)
            .findFirst().orElseThrow();
        open.state = Claim.State.LOST; // its ttl ran out, or the coordinator dropped it
        sim.tick();
        assertEquals(1, sim.bot.menuDrops.size(), "the menu closed on the next tick: " + sim.state());
        // the engine retries with a new claim and finishes the map
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(coord.claims("chest:").size() > 1, "asked again");
        assertTrue(coord.held().isEmpty(), "every claim released: " + coord.held());
    }

    /** A map set aside for lack of materials goes back to the coordinator as out_of_materials (prompt 2). */
    @Test
    void aShortMapIsGivenBackOutOfMaterials() {
        FakeCoordination[] c = new FakeCoordination[1];
        Sim sim = seam(c);
        FakeCoordination coord = c[0];
        sim.config.build.nearScanFirst = false;
        Job a = sim.addJob("j0114", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.LIME, 2));
        Job b = sim.addJob("j0115", "Plain", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        coord.grant(a, 0, 0);
        coord.grant(b, 0, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> b.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(coord.reports.contains("release " + a.id + "/0,0 out_of_materials"), coord.reports.toString());
        assertTrue(coord.reports.contains("done " + b.id + "/0,0"), coord.reports.toString());
    }
}
