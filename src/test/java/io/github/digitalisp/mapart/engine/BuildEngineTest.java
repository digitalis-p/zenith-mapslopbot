package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.BLACK;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

class BuildEngineTest {
    private static final int LONG = 60_000;

    private static void assertDone(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void happyPathMoss() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(8, 2);
        Job job = sim.addJob("j0001", "Stripes", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        AtomicBoolean modulesOffWhileBuilding = new AtomicBoolean();
        AtomicBoolean sawFullArt = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> {
            if (sim.phase() == Phase.BUILD && !sim.modules.isEnabled("AntiAFK") && !sim.modules.isEnabled("KillAura")
                && !sim.modules.isEnabled("AutoEat")) modulesOffWhileBuilding.set(true);
            if (sim.phase() == Phase.STORE && sim.matchesArt(sim.moss, art)) sawFullArt.set(true);
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), sim.state());

        assertTrue(sawFullArt.get(), "art was complete before storing");
        assertEquals(TileStatus.STORED, job.tiles.getFirst().status);
        assertEquals("Stripes", job.tiles.getFirst().mapName);
        assertEquals(1, sim.output().maps.size(), "one map stored");
        FakeBot.MapItem stored = sim.output().maps.getFirst();
        assertTrue(stored.locked());
        assertEquals("Stripes", stored.name());
        assertEquals(0, sim.artBlocks(sim.moss), "platform cleared");
        assertEquals(0, sim.machineBroken, "no full blocks reached the flying machines");
        assertFalse(sim.bot.placedWithToggleOn, "item collection was off while building");
        assertFalse(sim.cartsOn(), "item collection off at the end");
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_CARTS_ON), "carts switched on for the nuke");
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_CARTS_OFF), "carts switched off after the clear");
        assertTrue(modulesOffWhileBuilding.get(), "AntiAFK/KillAura/AutoEat suppressed while building");
        assertTrue(sim.modules.isEnabled("AntiAFK") && sim.modules.isEnabled("KillAura")
            && sim.modules.isEnabled("AutoEat"), "modules restored when idle");
        assertEquals(1, sim.bot.createdMaps);
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed once: " + e.getKey());
        assertTrue(sim.notifier.has("info", "Started"));
        assertTrue(sim.notifier.has("detail", "Tile built"));
        assertTrue(sim.notifier.has("detail", "Map locked"));
        assertTrue(sim.notifier.has("detail", "Clearing"));
        assertTrue(sim.notifier.has("info", "Job done"));
        // one always-posted message per map: "Map done" with the final render
        assertEquals(1, sim.notifier.messages.stream().filter(m -> m.title().startsWith("Map done")).count(),
            sim.notifier.toString());
        assertTrue(sim.notifier.has("image", "Map done: Stripes"), "final render posted: " + sim.notifier);
        assertEquals(1, sim.renderer.mapData);
        assertTrue(sim.notifier.of("alert").isEmpty(), "no alerts: " + sim.notifier);
        // the return chest inside the material region was never treated as material stock
        assertFalse(sim.store.stock.containers.containsKey(Sim.RETURN.key()));
        assertEquals(5, sim.store.stock.containers.size());
        assertEquals("IDLE", sim.store.checkpoint.phase);
    }

    @Test
    void limitedViewDistance() {
        Sim sim = new Sim();
        // only chunks within 6 of the player are loaded: storage and platform never see each other
        sim.world.loaded = p -> Math.max(Math.abs(Math.floorDiv(p.x(), 16) - Math.floorDiv((int) Math.floor(sim.world.x), 16)),
            Math.abs(Math.floorDiv(p.z(), 16) - Math.floorDiv((int) Math.floor(sim.world.z), 16))) <= 6;
        TileArt art = Sim.stripes(30, 2);
        Job job = sim.addJob("j0030", "Far", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertDone(sim, job);
        assertEquals(1, sim.output().maps.size());
        assertEquals(0, sim.machineBroken);
        // the first restock happened straight after the stock check, without a detour to the platform
        List<String> h = sim.bot.history;
        int firstWithdraw = h.indexOf(h.stream().filter(s -> s.startsWith("withdraw [-")).findFirst().orElseThrow());
        int lastScan = h.lastIndexOf(h.stream().filter(s -> s.startsWith("scan")).reduce((a, b) -> b).orElseThrow());
        assertTrue(h.subList(lastScan, firstWithdraw).stream().noneMatch(s -> s.equals("walkTo [0,64,0]")), h.toString());
    }

    @Test
    void happyPathSoulSandWithBootsAndMending() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob("j0002", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        AtomicBoolean leftCentreDuringClear = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> {
            if (sim.phase() == Phase.WAIT_CLEAR && sim.bot.lastTask != null) {
                double dx = sim.world.x - 256.5, dz = sim.world.z - 0.5;
                if (sim.engine.status().phaseSince() + 2_000 < sim.clock.millis() && dx * dx + dz * dz > 16)
                    leftCentreDuringClear.set(true);
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals("minecraft:netherite_boots", sim.inv.boots, "boots equipped");
        assertTrue(sim.inv.bootsDurability >= 0.95, "boots mended: " + sim.inv.bootsDurability);
        assertTrue(sim.bot.bottlesThrown > 0);
        assertEquals(1, sim.output().maps.size());
        assertEquals("Soul", sim.output().maps.getFirst().name());
        assertEquals(0, sim.artBlocks(sim.soul));
        assertFalse(leftCentreDuringClear.get(), "stayed at the center while the clear ran");
        assertEquals(0, sim.artBlocks(sim.moss), "moss platform untouched");
    }

    @Test
    void twoByOneJobBuildsBothTiles() {
        Sim sim = new Sim();
        TileArt left = Sim.single(WHITE, 4);
        TileArt right = Sim.single(RED, 4);
        Job job = sim.addJob("j0003", "Wide", 2, 1, List.of("moss"), (x, z) -> x == 0 ? left : right);
        sim.start();
        assertDone(sim, job);
        assertEquals(2, sim.output().maps.size());
        assertEquals("Wide 0,0", sim.output().maps.get(0).name());
        assertEquals("Wide 1,0", sim.output().maps.get(1).name());
        assertTrue(job.tiles.stream().allMatch(t -> t.status == TileStatus.STORED));
        assertTrue(sim.notifier.has("detail", "Tile done"));
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().contains("Job done") && m.body().contains("Wide 1,0")));
    }

    @Test
    void multipleRestockLoads() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(40, 0); // 5120 blocks, more than one inventory load
        Job job = sim.addJob("j0004", "Big", 1, 1, List.of("moss"), (x, z) -> art);
        sim.bot.containers.get(new Pos(-96, 61, 100)).items.put(RED, 3000);
        sim.bot.containers.get(new Pos(-94, 61, 100)).items.put(BLACK, 3000);
        sim.bot.placementsPerTick = 4; // slow enough for a progress report (interval 1 min)
        sim.start();
        assertDone(sim, job);
        assertEquals(1, sim.store.entries("VERIFY"), "loads running out go to RESTOCK, not VERIFY: " + sim.store.phaseLog);
        assertTrue(sim.bot.count("withdraw") >= 6, "several restock trips: " + sim.bot.count("withdraw"));
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed once: " + e.getKey());
        assertTrue(sim.notifier.progressCalls > 0, "periodic progress posted");
    }

    @Test
    void fullTileNeedsManyLoadsWithoutRepairPasses() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(128, 0); // 16384 blocks, about 9 inventory loads
        Job job = sim.addJob("j0023", "Full", 1, 1, List.of("moss"), (x, z) -> art);
        for (int i = 0; i < 4; i++) {
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 104), Map.of(Sim.WHITE, 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 106), Map.of(Sim.RED, 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 108), Map.of(Sim.BLACK, 1728));
        }
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.store.entries("RESTOCK") >= 8, "restock rounds: " + sim.store.entries("RESTOCK"));
        assertEquals(1, sim.store.entries("VERIFY"), sim.store.phaseLog.toString());
        assertEquals(16384, sim.bot.placed);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void shortageHoldsJobAndOtherJobProceeds() {
        Sim sim = new Sim();
        // Exercise the legacy STOCK_CHECK hold sequence; near-first SELECT can discover Plain before activation.
        sim.config.build.nearScanFirst = false;
        Job a = sim.addJob("j0005", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        Job b = sim.addJob("j0006", "Plain", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> b.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(JobStatus.WAITING_MATERIALS, a.status);
        assertEquals("short: 256 lime carpet", a.statusDetail);
        // set aside while another map can be built: named in that map's start line, no alert
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.title().equals("Started Plain instead of Lime")
            && m.body().contains("Skipped for now: Lime needs 256 more lime carpet.")), sim.notifier.toString());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, LONG), sim.state());
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Out of lime carpet", alerts.getFirst().title());
        // no container at the base has ever held lime carpet: said so, in plain words
        assertTrue(alerts.getFirst().body().contains("\nLime needs:\n- lime carpet: 256 more needed (4 stacks; no chest at this base"
            + " holds it)"), alerts.getFirst().body());
        assertFalse(alerts.getFirst().body().contains("→") || alerts.getFirst().body().contains("minecraft:"),
            alerts.getFirst().body());
        // materials arrive: the waiting job is re-checked and built
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(LIME, 1728));
        sim.engine.requestStockScan();
        assertTrue(sim.runUntil(() -> a.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(2, sim.output().maps.size());
    }

    @Test
    void shortageWithAChestForTheItemKeepsTheMissingCount() {
        Sim sim = new Sim();
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(LIME, 10));
        Job a = sim.addJob("j0005", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> a.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE, LONG),
            sim.state());
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().startsWith("Nothing to build: the queued map is short of materials. Put them in"
            + " the storage chests; the bot looks again every 10 min.\nLime needs:\n- lime carpet: 246 more needed"), alerts.getFirst().body());
        assertFalse(alerts.getFirst().body().contains("no chest"), alerts.getFirst().body());
    }

    @Test
    void resumeAfterBotTicksStopAndStart() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0007", "Resume", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 600 && sim.phase() == Phase.BUILD, LONG), sim.state());
        int before = sim.bot.placed;
        BotTaskProbe probe = new BotTaskProbe(sim.bot.lastTask);
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        assertTrue(probe.cancelled(), "running task cancelled on stop");
        assertTrue(sim.modules.isEnabled("AntiAFK"), "modules restored while a player controls the bot");
        sim.run(5); // the adapter would not tick while stopped, but extra ticks must be harmless
        sim.engine.onBotTickStarting();
        assertDone(sim, job);
        assertTrue(before < art.nonEmptyCount());
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "rebuilt a correct block at " + e.getKey());
        assertTrue(sim.matchesArt(sim.moss, art) || sim.artBlocks(sim.moss) == 0);
    }

    @Test
    void resumeAfterRestartFromCheckpoint() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0008", "Restart", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 600 && sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals("BUILD", sim.store.checkpoint.phase);
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        // process restart: new engine, same world/inventory/store; map ids are a new session
        sim.newEngine();
        sim.start();
        assertDone(sim, job);
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "rebuilt a correct block at " + e.getKey());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void restartDuringClearDoesNotRebuild() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0009", "Late", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(4, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, LONG), sim.state());
        assertTrue(sim.store.checkpoint.stored);
        sim.engine.shutdown();
        sim.newEngine();
        sim.start();
        assertDone(sim, job);
        assertEquals(1, sim.output().maps.size(), "no second map");
        assertEquals(1, sim.bot.createdMaps);
    }

    @Test
    void wrongBlockIsRepaired() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Job job = sim.addJob("j0010", "Repair", 1, 1, List.of("moss"), (x, z) -> art);
        Pos victim = sim.moss.geometry().cell(10, 2);
        AtomicInteger injected = new AtomicInteger();
        sim.bot.placeOverride = p -> {
            if (p.pos().equals(victim) && injected.getAndIncrement() == 0)
                return new Placement(p.pos(), BlockSpec.of(RED), RED);
            return null;
        };
        AtomicBoolean sawWrong = new AtomicBoolean();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            if (sim.world.block(victim).id().equals(RED)) sawWrong.set(true);
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(sawWrong.get());
        assertTrue(injected.get() >= 1);
        assertTrue(sim.bot.history.stream().anyMatch(h -> h.equals("breakBlocks 1")), "wrong block broken");
        assertEquals(2, sim.bot.placeCounts.get(victim), "victim placed twice");
        assertEquals(1, sim.bot.createdMaps, "map only made after the tile matched");
    }

    @Test
    void wrongBlockIsLeftToVerifyNotRetriedEveryRound() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(40, 0); // several restock rounds
        Job job = sim.addJob("j0029", "Rounds", 1, 1, List.of("moss"), (x, z) -> art);
        sim.bot.containers.get(new Pos(-96, 61, 100)).items.put(RED, 3000);
        sim.bot.containers.get(new Pos(-94, 61, 100)).items.put(BLACK, 3000);
        Pos victim = sim.moss.geometry().cell(1, 3); // in the first lane
        AtomicInteger injected = new AtomicInteger();
        sim.bot.placeOverride = p -> p.pos().equals(victim) && injected.getAndIncrement() == 0
            ? new Placement(p.pos(), BlockSpec.of(LIME), LIME) : null;
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.store.entries("RESTOCK") >= 3, sim.store.phaseLog.toString());
        long lanesWithVictim = sim.bot.lanesRun.stream()
            .filter(l -> l.placements().stream().anyMatch(p -> p.pos().equals(victim))).count();
        assertEquals(2, lanesWithVictim, "first build lane + one repair lane, not every restock round");
        assertEquals(2, sim.bot.placeCounts.get(victim), "replaced once in VERIFY");
    }

    @Test
    void mapMismatchRepairsAndHoldsAgain() {
        Sim sim = new Sim();
        TileArt art = Sim.single(WHITE, 4);
        Job job = sim.addJob("j0011", "Mismatch", 1, 1, List.of("moss"), (x, z) -> art);
        Pos victim = sim.moss.geometry().cell(20, 1);
        sim.bot.beforeCreateMap = () -> sim.world.set(victim, BlockSpec.of(BLACK));
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.notifier.has("image", "Map mismatch"), sim.notifier.toString());
        assertEquals(1, sim.renderer.diffs);
        assertEquals(1, sim.bot.createdMaps, "existing map re-held, not a new one");
        assertTrue(sim.bot.heldMaps >= 1);
        assertEquals(1, sim.output().maps.size());
        assertEquals(1, sim.notifier.of("detail").stream().filter(m -> m.title().equals("Tile built")).count());
    }

    @Test
    void foreignBlocksAreNukedAndClearedBeforeBuilding() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.world.set(sim.moss.geometry().cell(100 + i % 10, 100 + i / 10), BlockSpec.of(Sim.TERRACOTTA));
            sim.world.set(sim.moss.geometry().cell(50 + i % 10, 60 + i / 10), BlockSpec.of(RED));
        }
        TileArt art = Sim.single(WHITE, 2);
        Job job = sim.addJob("j0012", "Fresh", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertTrue(sim.notifier.has("info", "Clearing platform first"));
        assertEquals(0, sim.machineBroken, "terracotta was nuked before the clear");
        assertDone(sim, job);
    }

    @Test
    void foreignBlocksReappearingAlertThenBlock() {
        Sim sim = new Sim();
        Pos spot = sim.moss.geometry().cell(5, 5);
        sim.store.onSave = cp -> {
            if ("INSPECT".equals(cp.phase)) sim.world.set(spot, BlockSpec.of(RED)); // someone keeps dropping a carpet
        };
        sim.addJob("j0024", "Griefed", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.notifier.has("info", "Clearing platform first"));
        assertTrue(sim.notifier.has("alert", "Platform not clear again"));
        assertTrue(sim.engine.status().note().contains("still not clear after 2 clears"), sim.state());
        assertEquals(2, sim.bot.count("use " + Sim.MOSS_BUTTON));
    }

    @Test
    void actuatorOnTheArtLayerIsIgnored() {
        Sim sim = new Sim();
        Pos middle = sim.moss.geometry().cell(64, 64);
        sim.world.set(middle, BlockSpec.of("minecraft:stone_button", Map.of("face", "floor", "facing", "north", "powered", "false")));
        sim.bot.useHandlers.put(middle, sim.bot.useHandlers.get(Sim.MOSS_BUTTON));
        sim.moss.clear.getFirst().pos = middle;
        Job job = sim.addJob("j0025", "Button", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        assertEquals("minecraft:stone_button", sim.world.block(middle).id(), "button never broken");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void clearTimeoutRetriesThenBlocksThenResumes() {
        Sim sim = new Sim();
        sim.clearWorks = false;
        Job job = sim.addJob("j0013", "Stuck", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertEquals(3, sim.bot.count("use " + Sim.MOSS_BUTTON), "1 press + clearRetries (2)");
        assertTrue(sim.notifier.has("alert", "Map art blocked"));
        assertTrue(sim.engine.status().note().contains("not clear after 3 clear attempts"), sim.state());
        assertEquals("WAIT_CLEAR", sim.store.checkpoint.blockedFrom);
        assertEquals("BLOCKED", sim.store.checkpoint.phase);
        assertTrue(sim.store.checkpoint.note.startsWith("platform moss not clear"), sim.store.checkpoint.note);
        assertTrue(sim.modules.isEnabled("AntiAFK"), "modules restored while blocked");
        sim.run(500);
        assertEquals(Phase.BLOCKED, sim.phase(), "stays blocked until resume");
        assertEquals(3, sim.bot.count("use " + Sim.MOSS_BUTTON));
        sim.clearWorks = true;
        sim.engine.resume();
        assertDone(sim, job);
        assertTrue(sim.notifier.has("info", "Resumed"));
    }

    @Test
    void blockedStateSurvivesRestart() {
        Sim sim = new Sim();
        sim.clearWorks = false;
        Job job = sim.addJob("j0014", "StuckRestart", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        sim.engine.shutdown();
        sim.newEngine();
        sim.start();
        sim.run(200);
        assertEquals(Phase.BLOCKED, sim.phase(), sim.state());
        sim.clearWorks = true;
        sim.engine.resume();
        assertDone(sim, job);
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void resumeRequestedBeforeRestoreUnblocks() {
        Sim sim = new Sim();
        sim.clearWorks = false;
        Job job = sim.addJob("j0026", "EarlyResume", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        sim.engine.shutdown();
        sim.newEngine();
        sim.clearWorks = true;
        sim.engine.resume(); // e.g. "!map resume" right after a restart, before the first tick
        sim.start();
        assertDone(sim, job);
    }

    @Test
    void jobCancelledWhileOfflineStillClearsPlatform() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0027", "Offline", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(20, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 400 && sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        assertTrue(sim.store.cancel(job.id)); // cancelled through Discord while ZenithProxy was down
        sim.newEngine();
        sim.start();
        sim.tick();
        assertEquals(Phase.NUKE, sim.phase(), "restored straight into clean-up: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.notifier.has("info", "Cancelled job cleaned up") && sim.phase() == Phase.IDLE, LONG), sim.state());
        assertEquals(JobStatus.CANCELLED, job.status);
        assertEquals(0, sim.artBlocks(sim.moss), "platform cleared");
        assertEquals(0, sim.machineBroken);
        assertEquals(0, sim.bot.createdMaps);
        assertFalse(sim.cartsOn());
    }

    @Test
    void leverClearActuatorIsHeldThenReleased() {
        Sim sim = new Sim();
        Pos lever = new Pos(-70, 64, -6);
        sim.world.set(lever, BlockSpec.of("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "false")));
        AtomicInteger presses = new AtomicInteger();
        sim.bot.useHandlers.put(lever, () -> {
            if (presses.getAndIncrement() % 2 == 0) sim.bot.useHandlers.get(Sim.MOSS_BUTTON).run();
        });
        sim.moss.clear.getFirst().pos = lever;
        sim.moss.clear.getFirst().mode = "LEVER";
        sim.moss.clear.getFirst().holdTicks = 40;
        Job job = sim.addJob("j0028", "Lever", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        assertEquals(2, sim.bot.count("use " + lever), "on, then off");
        assertTrue(sim.bot.history.contains("wait 40"));
        assertEquals("false", sim.world.block(lever).props().get("powered"));
    }

    @Test
    void pauseAndResume() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0015", "Pause", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD, LONG), sim.state());
        BotTaskProbe probe = new BotTaskProbe(sim.bot.lastTask);
        sim.engine.pause("lunch");
        assertTrue(sim.engine.status().paused());
        assertEquals("lunch", sim.engine.status().pauseReason());
        assertTrue(sim.config.paused);
        sim.tick();
        assertTrue(probe.cancelled(), "task cancelled on pause");
        int placed = sim.bot.placed;
        int tasks = sim.bot.history.size();
        sim.run(400);
        assertEquals(placed, sim.bot.placed, "nothing placed while paused");
        assertEquals(tasks, sim.bot.history.size(), "no tasks while paused");
        assertTrue(sim.modules.isEnabled("AntiAFK"), "modules restored while paused");
        assertEquals(Phase.BUILD, sim.phase());
        sim.engine.resume();
        assertFalse(sim.engine.status().paused());
        assertDone(sim, job);
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue());
    }

    @Test
    void cancelMidBuildClearsPlatform() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(20, 2);
        Job job = sim.addJob("j0016", "Cancel", 1, 1, List.of("moss"), (x, z) -> art);
        Job next = sim.addJob("j0017", "Next", 1, 1, List.of("moss"), (x, z) -> Sim.single(RED, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 400 && sim.phase() == Phase.BUILD, LONG), sim.state());
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> next.status == JobStatus.ACTIVE, LONG), sim.state());
        assertEquals(JobStatus.CANCELLED, job.status);
        assertTrue(sim.notifier.has("info", "Cancelled job cleaned up"), sim.notifier.toString());
        assertEquals(0, sim.bot.createdMaps, "no map for a cancelled job");
        assertEquals(0, sim.machineBroken);
        assertTrue(sim.runUntil(() -> next.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.output().maps.size());
        assertEquals("Next", sim.output().maps.getFirst().name());
    }

    @Test
    void transientFailuresAreRetried() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0018", "Flaky", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.bot.failNext.put("createMap", 2);
        sim.bot.failNext.put("walk", 1);
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void persistentFailureBlocks() {
        Sim sim = new Sim();
        sim.addJob("j0019", "Broken", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.bot.failNext.put("lockMap", 1000);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("LOCK"), sim.state());
        long locks = sim.bot.count("lockMap");
        assertEquals(EngineTuning.MAX_TASK_RETRIES + 1, locks);
        sim.run(2_000);
        assertEquals(locks, sim.bot.count("lockMap"), "no retries while blocked");
    }

    @Test
    void gearWithdrawsToolsAndFood() {
        Sim sim = new Sim();
        sim.inv.items.clear();
        Job job = sim.addJob("j0020", "Gear", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.inv.hasTools());
        assertTrue(sim.inv.foodCount() >= EngineTuning.FOOD_MIN);
    }

    @Test
    void brokenAnvilIsReplaced() {
        Sim sim = new Sim();
        sim.world.set(Sim.ANVIL, BlockSpec.AIR);
        Job job = sim.addJob("j0021", "Anvil", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.world.block(Sim.ANVIL).id().equals("minecraft:anvil"));
        assertEquals("Anvil", sim.output().maps.getFirst().name());
    }

    @Test
    void statusReportsCurrentWork() {
        Sim sim = new Sim();
        sim.addJob("j0022", "Status", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(10, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        var s = sim.engine.status();
        assertNotNull(s);
        assertEquals("j0022", s.jobId());
        assertEquals("moss", s.platformId());
        assertEquals(1280, s.total());
        assertTrue(s.enabled());
    }

    /** Remembers whether a task was cancelled (FakeBot tasks mark it in release()). */
    private record BotTaskProbe(io.github.digitalisp.mapart.bot.BotTask task) {
        boolean cancelled() {
            return task.isFinished() && task.failure() != null && task.failure().endsWith("cancelled");
        }
    }
}
