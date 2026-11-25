package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/** Site-specific behaviour: two-sided clear buttons, moss item collection, leftover drops, map handling, checkpoints. */
class SiteFeaturesTest {
    private static final int LONG = 60_000;

    private static void assertDone(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    // ---- clear buttons with side probes ------------------------------------------------------

    @Test
    void clearPressesOnlyTheButtonOnTheMachinesSide() {
        Sim sim = new Sim();
        sim.twoSidedClear(true);
        Job job = sim.addJob("j0101", "Sides", 2, 1, List.of("moss"), (x, z) -> Sim.single(x == 0 ? WHITE : RED, 3));
        sim.start();
        assertDone(sim, job);
        assertEquals(2, sim.output().maps.size());
        assertEquals(0, sim.artBlocks(sim.moss), "cleared after the last tile");
        assertEquals(0, sim.wrongSidePresses, "never pressed the side without machines");
        assertEquals(1, sim.bot.count("use " + Sim.EAST_BUTTON), "first clear from the east");
        assertEquals(1, sim.bot.count("use " + Sim.WEST_BUTTON), "second clear from the west");
        int east = sim.bot.history.indexOf("use " + Sim.EAST_BUTTON);
        int west = sim.bot.history.indexOf("use " + Sim.WEST_BUTTON);
        assertTrue(east < west, sim.bot.history.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void noApplicableClearButtonBlocksWithAClearMessage() {
        Sim sim = new Sim();
        sim.twoSidedClear(true);
        sim.world.set(Sim.EAST_PROBE, BlockSpec.AIR); // machines neither east nor west: stuck mid-way
        Job job = sim.addJob("j0102", "Stuck", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.startsWith("no clear button applies on moss"), note);
        assertTrue(note.contains("probe " + Sim.EAST_PROBE.key() + " is minecraft:air, wants minecraft:slime_block"), note);
        assertEquals(0, sim.bot.count("use " + Sim.EAST_BUTTON) + sim.bot.count("use " + Sim.WEST_BUTTON));
        assertEquals("CLEAR", sim.store.checkpoint.blockedFrom);
        assertTrue(sim.notifier.has("alert", "Map art blocked"));
        // machines found and parked west again: resume presses the west button
        sim.world.set(Sim.WEST_PROBE, BlockSpec.of(Sim.SLIME));
        sim.engine.resume();
        assertDone(sim, job);
        assertEquals(1, sim.bot.count("use " + Sim.WEST_BUTTON));
        assertEquals(0, sim.wrongSidePresses);
    }

    @Test
    void probesThatAreNotLoadedAreWalkedTo() {
        Sim sim = new Sim();
        sim.twoSidedClear(false);
        AtomicBoolean nearButtons = new AtomicBoolean();
        // probes load only once the bot has walked to a clear button
        sim.world.loaded = p -> !(p.equals(Sim.EAST_PROBE) || p.equals(Sim.WEST_PROBE)) || nearButtons.get();
        Job job = sim.addJob("j0103", "Far", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> {
            if (sim.bot.history.contains("walkTo " + Sim.EAST_BUTTON)) nearButtons.set(true);
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertTrue(nearButtons.get(), sim.bot.history.toString());
        assertEquals(1, sim.bot.count("use " + Sim.WEST_BUTTON));
    }

    // ---- moss item collection ----------------------------------------------------------------

    @Test
    void cartsLeftOnAreSwitchedOffBeforeBuilding() {
        Sim sim = new Sim();
        sim.setCarts(true); // e.g. a previous clear switched them on
        Job job = sim.addJob("j0104", "Carts", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        List<String> h = sim.bot.history;
        int off = h.indexOf("use " + Sim.MOSS_CARTS_OFF);
        int firstLane = h.indexOf(h.stream().filter(s -> s.startsWith("placeLane")).findFirst().orElseThrow());
        assertTrue(off >= 0 && off < firstLane, "carts off before the first lane: " + h);
        assertFalse(sim.bot.placedWithToggleOn);
        assertFalse(sim.cartsOn());
        assertEquals(2, sim.bot.count("use " + Sim.MOSS_CARTS_OFF), "at GEAR and at FINISH_TILE");
    }

    @Test
    void collectionWithoutIndicatorIsPressedOncePerPhase() {
        Sim sim = new Sim();
        sim.moss.collectionIndicator = null;
        Job job = sim.addJob("j0105", "Blind", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_CARTS_ON), "NUKE");
        assertEquals(2, sim.bot.count("use " + Sim.MOSS_CARTS_OFF), "GEAR and FINISH_TILE");
        assertFalse(sim.cartsOn());
    }

    @Test
    void collectionThatDoesNotSwitchFailsTheStepThenBlocks() {
        Sim sim = new Sim();
        sim.bot.useHandlers.put(Sim.MOSS_CARTS_OFF, () -> { }); // broken "off" note block
        sim.setCarts(true);
        sim.addJob("j0106", "Broken", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("item collection did not switch off"), sim.state());
        assertEquals(0, sim.bot.placed, "nothing built with the carts on");
    }

    // ---- leftovers ------------------------------------------------------------------------------

    @Test
    void leftoversAreDroppedWhenThereIsNoReturnChest() {
        Sim sim = new Sim();
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        // leftovers are materials only: the sorter has chests for both (only materials are ever dropped)
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of("minecraft:cobblestone", 64));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(LIME, 64));
        sim.inv.add("minecraft:cobblestone", 100);
        sim.inv.add(LIME, 30);
        sim.inv.add("minecraft:experience_bottle", 5);
        Job job = sim.addJob("j0107", "Drop", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        // 8 cobblestone stay: the fall rescue's climbing kit (build.fallRescueKit; cobblestone is the first entry of
        // build.fallRescueBlocks the material chests hold here)
        assertEquals(Map.of("minecraft:cobblestone", 100 - sim.config.build.fallRescueKit, LIME, 30), sim.bot.droppedItems);
        assertTrue(sim.bot.history.contains("dropItems " + drop.standAt + " -> " + drop.target), sim.bot.history.toString());
        assertTrue(sim.inv.hasTools(), "tools kept");
        assertTrue(sim.inv.foodCount() > 0, "food kept");
        assertFalse(sim.bot.droppedItems.containsKey("minecraft:experience_bottle"), "XP bottles are never dropped");
    }

    @Test
    void returnChestIsPreferredOverDropPoints() {
        Sim sim = new Sim();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of("minecraft:cobblestone", 64)); // a sorter item: a material
        sim.inv.add("minecraft:cobblestone", 64);
        Job job = sim.addJob("j0108", "Chest", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, job);
        assertTrue(sim.bot.droppedItems.isEmpty());
        // all but the fall rescue's climbing kit (build.fallRescueKit)
        assertEquals(64 - sim.config.build.fallRescueKit, sim.bot.containers.get(Sim.RETURN).items.get("minecraft:cobblestone"));
    }

    // ---- map handling ---------------------------------------------------------------------------

    @Test
    void storeDepositsOnlyTheCurrentLockedMap() {
        Sim sim = new Sim();
        // an unrelated filled map (e.g. the owner's base map) and an old locked map stay in the inventory
        sim.inv.filledMaps.add(7);
        sim.inv.filledMaps.add(8);
        sim.maps.locked.add(8);
        sim.maps.colors.put(7, new byte[128 * 128]);
        sim.maps.colors.put(8, new byte[128 * 128]);
        Job job = sim.addJob("j0109", "Only", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job);
        assertEquals(1, sim.output().maps.size());
        FakeBot.MapItem stored = sim.output().maps.getFirst();
        assertTrue(stored.id() != 7 && stored.id() != 8, "stored the new map: " + stored);
        assertTrue(stored.locked());
        assertEquals("Only", stored.name());
        assertTrue(sim.inv.filledMaps.containsAll(List.of(7, 8)), "other maps kept: " + sim.inv.filledMaps);
    }

    @Test
    void renameIsSkippedWhenTheMapAlreadyHasTheName() {
        Sim sim = new Sim();
        AtomicInteger renamesBefore = new AtomicInteger(-1);
        Job job = sim.addJob("j0110", "Named", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        // the rename went through, then ZenithProxy restarted before STORE: the checkpoint still says RENAME
        sim.store.onSave = cp -> {
            if ("RENAME".equals(cp.phase) && renamesBefore.get() < 0) {
                renamesBefore.set((int) sim.bot.count("rename"));
                for (int id : sim.inv.filledMaps) if (sim.maps.locked.contains(id)) sim.inv.mapNames.put(id, "Named");
            }
        };
        sim.start();
        assertDone(sim, job);
        assertEquals(0, renamesBefore.get());
        assertEquals(0, sim.bot.count("rename"), "no anvil use");
        assertEquals(0, sim.bot.count("throwXp"), "no XP spent");
        assertEquals("Named", sim.output().maps.getFirst().name());
        assertEquals("Named", job.tiles.getFirst().mapName);
    }

    // ---- checkpoint -----------------------------------------------------------------------------

    @Test
    void legacyBlockedNoteEncodingIsStillRead() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0111", "Legacy", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        job.status = JobStatus.ACTIVE;
        job.tiles.getFirst().status = TileStatus.ACTIVE;
        job.tiles.getFirst().platformId = "moss";
        var cp = sim.store.checkpoint;
        cp.jobId = job.id;
        cp.platformId = "moss";
        cp.phase = "BLOCKED";
        cp.note = "[NUKE] fluid on the art layer of moss";
        sim.start();
        sim.run(100);
        assertEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals("fluid on the art layer of moss", sim.engine.status().note());
        List<String> before = new ArrayList<>(sim.store.phaseLog);
        sim.engine.resume();
        sim.run(5);
        List<String> after = sim.store.phaseLog.subList(before.size(), sim.store.phaseLog.size());
        assertEquals("NUKE", after.getFirst(), after.toString());
        assertDone(sim, job);
    }

    @Test
    void blockedFromIsStoredInItsOwnField() {
        Sim sim = new Sim();
        sim.addJob("j0112", "Field", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.bot.failNext.put("lockMap", 1000);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertEquals("LOCK", sim.store.checkpoint.blockedFrom);
        assertFalse(sim.store.checkpoint.note.startsWith("["), sim.store.checkpoint.note);
        sim.engine.resume();
        sim.run(3);
        assertEquals(null, sim.store.checkpoint.blockedFrom, "cleared once no longer blocked");
    }

    // ---- stock scans ----------------------------------------------------------------------------

    @Test
    void unreachableMaterialChestIsSkippedAfterTwoFailedScans() {
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = 1;
        Pos high = new Pos(-90, 62, 104);
        sim.bot.addContainer(high, Map.of(WHITE, 64));
        sim.bot.unreachable.add(high);
        Job a = sim.addJob("j0115", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, a);
        sim.clock.advance(120_000); // stock is stale again
        Job b = sim.addJob("j0116", "Second", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.engine.queueChanged();
        assertDone(sim, b);
        sim.clock.advance(120_000);
        Job c = sim.addJob("j0117", "Third", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.engine.queueChanged();
        assertDone(sim, c);
        assertEquals(2, sim.bot.count("scan " + high), "skipped after two failures: " + sim.bot.history);
        assertTrue(sim.notifier.has("alert", "Material chests unreachable"), sim.notifier.toString());
        // a requested scan tries it again
        sim.engine.requestStockScan();
        assertTrue(sim.runUntil(() -> sim.bot.count("scan " + high) == 3, LONG), sim.state());
    }

    @Test
    void staleRescanOnlyTouchesChestsWithNeededItems() {
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = 1;
        Job a = sim.addJob("j0118", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, a);
        long redScans = sim.bot.count("scan " + new Pos(-96, 61, 100));
        long whiteScans = sim.bot.count("scan " + new Pos(-100, 61, 100));
        sim.clock.advance(120_000); // everything is stale
        Job b = sim.addJob("j0119", "Second", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.engine.queueChanged();
        assertDone(sim, b);
        assertEquals(redScans, sim.bot.count("scan " + new Pos(-96, 61, 100)), "red chest not rescanned for a white job");
        assertTrue(sim.bot.count("scan " + new Pos(-100, 61, 100)) > whiteScans, "white chest rescanned");
    }

    @Test
    void shortageTriggersAFullRescanBeforeWaiting() {
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = 1;
        Job a = sim.addJob("j0120", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, a);
        // lime turns up in the (so far red) chest; the stock index does not know yet
        sim.bot.containers.get(new Pos(-96, 61, 100)).items.put(LIME, 300);
        sim.clock.advance(120_000);
        Job b = sim.addJob("j0121", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 1));
        sim.engine.queueChanged();
        assertDone(sim, b);
        assertTrue(sim.notifier.of("alert").stream().noneMatch(m -> m.title().contains("Materials short")), sim.notifier.toString());
    }

    @Test
    void shortageLooksAgainAtEmptyChestsInTheSameColumn() {
        Sim sim = new Sim();
        sim.config.build.nearRecheckAll = false; // the targeted look itself (on: every near container)
        Pos upper = new Pos(-100, 62, 100);   // empty chest above the white column
        Pos farEmpty = new Pos(-84, 62, 108); // empty chest elsewhere
        sim.bot.addContainer(upper, Map.of());
        sim.bot.addContainer(farEmpty, Map.of());
        Job a = sim.addJob("j0122", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertDone(sim, a);
        long farScans = sim.bot.count("scan " + farEmpty);
        // the sorter puts more white into the upper chest; snapshots are still fresh
        sim.bot.containers.get(upper).items.put(WHITE, 3000);
        sim.clock.advance(90_000);
        Job b = sim.addJob("j0123", "Lots", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 40)); // 5120 > 3456 known
        sim.engine.queueChanged();
        assertDone(sim, b);
        assertTrue(sim.notifier.of("alert").stream().noneMatch(m -> m.title().contains("Materials short")), sim.notifier.toString());
        assertEquals(farScans, sim.bot.count("scan " + farEmpty), "empty chest of another column not rescanned");
    }

    // ---- waiting jobs ---------------------------------------------------------------------------

    @Test
    void waitingJobIsRecheckedAfterWaitingRecheckMinutes() {
        Sim sim = new Sim();
        sim.config.build.waitingRecheckMinutes = 1;
        sim.config.build.stockMaxAgeMinutes = 1;
        Job job = sim.addJob("j0113", "Wait", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        // lime arrives in a known material chest; nobody asks for a scan
        sim.bot.containers.get(new Pos(-100, 61, 100)).items.put(LIME, 200);
        assertDone(sim, job);
    }

    @Test
    void waitingJobIsNotRecheckedBeforeTheInterval() {
        Sim sim = new Sim();
        sim.config.build.waitingRecheckMinutes = 30;
        sim.config.build.stockMaxAgeMinutes = 1;
        Job job = sim.addJob("j0114", "Patience", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        sim.bot.containers.get(new Pos(-100, 61, 100)).items.put(LIME, 200);
        long scans = sim.bot.count("scan");
        sim.run(20 * 60 * 10); // 10 minutes
        assertEquals(JobStatus.WAITING_MATERIALS, job.status);
        assertEquals(scans, sim.bot.count("scan"), "no rescans before 30 min");
        sim.run(20 * 60 * 25);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }
}
