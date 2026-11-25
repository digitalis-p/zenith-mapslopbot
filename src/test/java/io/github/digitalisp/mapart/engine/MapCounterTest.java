package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base's map counter (site.mapCounter): COUNT presses its button once per stored map, right after STORE, and
 * records it per tile so restarts neither skip nor repeat a press. The counter is never critical: a failure is one
 * alert and the job goes on. Synthetic coordinates: the Sim's output chest is at y 61, so a column is searched from
 * y 55 to 67.
 */
class MapCounterTest {
    private static final int LONG = 60_000;
    /** The counter column and its button, beside the Sim's supply chests. */
    private static final int CX = -55;
    private static final int CZ = 101;
    private static final Pos BUTTON = new Pos(CX, 62, CZ);

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.site.mapCounter = new MapArtConfig.ActuatorCfg();
        sim.config.site.mapCounterColumn = new MapArtConfig.ColumnCfg(CX, CZ);
        return sim;
    }

    private static BlockSpec oakButton(boolean powered) {
        return BlockSpec.of("minecraft:oak_button", Map.of("face", "wall", "facing", "south", "powered",
            String.valueOf(powered)));
    }

    private static Job pair(Sim sim) {
        return sim.addJob("j0500", "Pair", 2, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
    }

    private static Job single(Sim sim) {
        return sim.addJob("j0501", "Single", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
    }

    /** The checkpointed phases with repeats folded: the order the engine went through. */
    private static List<String> phases(Sim sim) {
        List<String> out = new ArrayList<>();
        for (String p : sim.store.phaseLog) if (out.isEmpty() || !out.getLast().equals(p)) out.add(p);
        return out;
    }

    private static long lines(RecordingLogger log, String prefix) {
        return log.lines.stream().filter(l -> l.contains(prefix)).count();
    }

    private static List<SimFakes.RecordingNotifier.Msg> counterAlerts(Sim sim) {
        return sim.notifier.of("alert").stream().filter(m -> m.title().equals("Map counter not pressed")).toList();
    }

    @Test
    void pressedOncePerStoredMapOfATwoByOneJobRightAfterStore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.world.set(BUTTON, oakButton(false));
        List<Integer> mapsStoredAtPress = new ArrayList<>();
        sim.bot.useHandlers.put(BUTTON, () -> mapsStoredAtPress.add(sim.output().maps.size()));
        Job job = pair(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(List.of(BUTTON, BUTTON), sim.bot.buttonPresses, "once per map id");
        assertEquals(List.of(1, 2), mapsStoredAtPress, "each press after its map is in the output chest");
        for (Job.TileState t : job.tiles) {
            assertEquals(TileStatus.STORED, t.status);
            assertTrue(t.counted, "tile " + t.coord());
        }
        List<String> order = phases(sim);
        int counts = 0;
        for (int i = 0; i < order.size(); i++) {
            if (!order.get(i).equals("COUNT")) continue;
            counts++;
            assertEquals("STORE", order.get(i - 1), order.toString());
            assertEquals("NUKE", order.get(i + 1), order.toString());
        }
        assertEquals(2, counts, order.toString());
        assertEquals(1, lines(log, "map counter button found at " + BUTTON.key()), "found once, logged once");
        assertEquals(BUTTON, sim.engine.mapCounterButton());
        assertEquals(List.of(), counterAlerts(sim));
    }

    @Test
    void theProductionPressRunsOncePerMapLookThenClick() {
        Sim sim = sim(new RecordingLogger());
        BlockSpec button = io.github.digitalisp.mapart.testutil.ContainerSim.button("wall", "north");
        sim.world.set(BUTTON, button);
        io.github.digitalisp.mapart.testutil.ContainerSim cs = sim.bot.simulateContainerAccess(52);
        cs.fill(-75, 60, 85, -35, 60, 115, io.github.digitalisp.mapart.testutil.ContainerSim.STONE); // the supply walkway
        cs.set(BUTTON.offset(0, 0, 1), io.github.digitalisp.mapart.testutil.ContainerSim.STONE);    // the button's wall
        cs.set(BUTTON, button);
        cs.allowedCells = io.github.digitalisp.mapart.bot.impl.UseRun.buttonStandCells(List.of(sim.moss.geometry(), sim.soul.geometry()));
        Job job = pair(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(List.of(BUTTON, BUTTON), sim.bot.buttonPresses);
        assertEquals(2, cs.clicks, "one click per map");
        assertEquals(0, cs.rotateAndClick, "looked on the tick before the click");
        assertEquals(0, cs.clickWhileMoving);
        assertEquals(List.of(), sim.bot.approachFailures);
        for (Job.TileState t : job.tiles) assertTrue(t.counted);
    }

    @Test
    void withTheCounterOffThereIsNoCountPhase() {
        Sim sim = new Sim();
        sim.world.set(BUTTON, oakButton(false));
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(phases(sim).contains("COUNT"), phases(sim).toString());
        assertEquals(List.of(), sim.bot.buttonPresses);
        assertFalse(job.tiles.getFirst().counted);
        assertNull(sim.engine.mapCounterButton());
    }

    @Test
    void notPressedForACancelledJob() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(false));
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> sim.notifier.has("info", "Cancelled job cleaned up"), LONG), sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, LONG), sim.state());

        assertEquals(List.of(), sim.bot.buttonPresses, "the unfinished map is not counted");
        assertFalse(job.tiles.getFirst().counted);
        assertFalse(phases(sim).contains("COUNT"), phases(sim).toString());
    }

    @Test
    void aMapStoredBeforeTheCancelIsStillCounted() {
        Sim sim = sim(new RecordingLogger());
        sim.config.site.mapCounterLoginDelaySeconds = 0; // the pop wait, not the login wait (MapCounterSafetyTest)
        sim.world.set(BUTTON, oakButton(false));
        Job job = pair(sim);
        // someone presses the counter just as the first map is stored: COUNT waits for it, and the cancel comes then
        AtomicBoolean held = new AtomicBoolean();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            if (!held.get() && sim.phase() == Phase.STORE) {
                held.set(true);
                sim.world.set(BUTTON, oakButton(true));
                sim.bot.schedule(100, () -> sim.world.set(BUTTON, oakButton(false)));
            }
            return sim.phase() == Phase.COUNT && BuildEngine.COUNTER_WAIT_NOTE.equals(sim.engine.status().note());
        }, LONG), sim.state());
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> sim.notifier.has("info", "Cancelled job cleaned up"), LONG), sim.state());

        assertEquals(List.of(BUTTON), sim.bot.buttonPresses, "the stored map is in the output chest: counted");
        assertTrue(job.tiles.get(0).counted);
        assertFalse(job.tiles.get(1).counted);
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void aRestartBetweenStoreAndThePressPressesOnce() {
        for (String savedPhase : List.of("COUNT", "STORE")) {
            Sim sim = sim(new RecordingLogger());
            sim.world.set(BUTTON, oakButton(false));
            Job job = single(sim);
            sim.start();
            assertTrue(sim.runUntil(() -> sim.phase() == Phase.COUNT, LONG), sim.state());
            Job.TileState t = job.tiles.getFirst();
            assertEquals(TileStatus.STORED, t.status);
            assertFalse(t.counted);
            assertEquals(List.of(), sim.bot.buttonPresses, "the press task has not run yet");

            // the plugin dies here: the job file says STORED, the checkpoint COUNT (or still STORE: its write was lost)
            sim.store.checkpoint.phase = savedPhase;
            sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), new RecordingLogger());
            sim.start();
            assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), savedPhase + ": " + sim.state());

            assertEquals(List.of(BUTTON), sim.bot.buttonPresses, "pressed once after the restart (" + savedPhase + ")");
            assertTrue(t.counted);
            assertEquals(1, sim.output().maps.size(), "the map was not stored twice");
        }
    }

    @Test
    void aRestartAfterThePressNeverPressesAgain() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(false));
        Job job = single(sim);
        sim.start();
        Job.TileState t = job.tiles.getFirst();
        assertTrue(sim.runUntil(() -> t.counted, LONG), sim.state());
        assertEquals(1, sim.bot.buttonPresses.size());

        // the plugin dies before the NUKE checkpoint is written: the checkpoint still says COUNT
        sim.store.checkpoint.phase = "COUNT";
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), new RecordingLogger());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(BUTTON), sim.bot.buttonPresses, "not pressed again");
    }

    @Test
    void noButtonInTheColumnSkipsTheCounterWithOneAlertAndNeverPressesTheOneBesideIt() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Pos beside = new Pos(CX + 1, 62, CZ);   // the column given one block off
        sim.world.set(beside, oakButton(false));
        Job job = pair(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(List.of(), sim.bot.buttonPresses, "nothing else is ever pressed");
        assertEquals("false", sim.world.block(beside).props().get("powered"));
        List<SimFakes.RecordingNotifier.Msg> alerts = counterAlerts(sim);
        assertEquals(1, alerts.size(), "alerted once, not per map: " + sim.notifier);
        String body = alerts.getFirst().body();
        assertTrue(body.contains("The map counter was not pressed: no button in column " + CX + "," + CZ + " (y 55 to 67)"), body);
        assertTrue(body.contains("buttons beside it: " + beside.key()), body);
        assertEquals(2, lines(log, "map counter not pressed for"), "logged for both maps");
        for (Job.TileState t : job.tiles) assertFalse(t.counted);
        assertFalse(phases(sim).contains("BLOCKED"), phases(sim).toString());
    }

    @Test
    void twoButtonsInTheColumnSkipTheCounterWithOneAlert() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(false));
        sim.world.set(BUTTON.above().above(), oakButton(false));
        Job job = pair(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(List.of(), sim.bot.buttonPresses, "it cannot tell which one counts");
        List<SimFakes.RecordingNotifier.Msg> alerts = counterAlerts(sim);
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("2 buttons in column " + CX + "," + CZ), alerts.getFirst().body());
        assertNull(sim.engine.mapCounterButton());
    }

    @Test
    void aButtonOutsideTheScannedHeightsIsNotFound() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(new Pos(CX, 61 + MapCounter.SCAN_ABOVE + 1, CZ), oakButton(false));
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(), sim.bot.buttonPresses);
        assertEquals(1, counterAlerts(sim).size(), sim.notifier.toString());
    }

    @Test
    void anExactPosIsPressedAndAnythingButAButtonThereIsNot() {
        Sim sim = sim(new RecordingLogger());
        sim.config.site.mapCounterColumn = null;
        sim.config.site.mapCounter.pos = BUTTON;
        sim.world.set(BUTTON, BlockSpec.of("minecraft:stone_button", Map.of("face", "floor", "facing", "north",
            "powered", "false")));
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(BUTTON), sim.bot.buttonPresses);

        Sim lever = sim(new RecordingLogger());
        lever.config.site.mapCounterColumn = null;
        lever.config.site.mapCounter.pos = BUTTON;
        lever.world.set(BUTTON, BlockSpec.of("minecraft:lever", Map.of("face", "floor", "facing", "north",
            "powered", "false")));
        Job j2 = single(lever);
        lever.start();
        assertTrue(lever.runUntil(() -> j2.status == JobStatus.DONE, LONG), lever.state());
        assertEquals(List.of(), lever.bot.buttonPresses);
        assertEquals("false", lever.world.block(BUTTON).props().get("powered"));
        assertTrue(counterAlerts(lever).getFirst().body().contains("holds lever, not a button"),
            lever.notifier.toString());
    }

    @Test
    void aButtonInAProtectedRegionIsNeverPressed() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(false));
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = BUTTON;
        r.max = BUTTON;
        r.name = "counter";
        sim.config.site.protectedRegions.add(r);
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(), sim.bot.buttonPresses);
        assertTrue(counterAlerts(sim).getFirst().body().contains("lies in protected region #1 \"counter\""),
            sim.notifier.toString());
    }

    @Test
    void anAlreadyPressedButtonIsWaitedForThenPressed() {
        Sim sim = sim(new RecordingLogger());
        sim.config.site.mapCounterLoginDelaySeconds = 0; // the pop wait, not the login wait (MapCounterSafetyTest)
        sim.world.set(BUTTON, oakButton(false));
        Job job = single(sim);
        long[] popAt = {-1};
        AtomicBoolean sawWaitNote = new AtomicBoolean();
        sim.start();
        // someone else presses it as the map is stored: still pressed when COUNT starts
        assertTrue(sim.runUntil(() -> {
            if (popAt[0] < 0 && sim.phase() == Phase.STORE) {
                sim.world.set(BUTTON, oakButton(true));
                popAt[0] = sim.ticks + 30;
                sim.bot.schedule(30, () -> sim.world.set(BUTTON, oakButton(false)));
            }
            if (sim.phase() == Phase.COUNT && BuildEngine.COUNTER_WAIT_NOTE.equals(sim.engine.status().note())) {
                sawWaitNote.set(true);
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());

        assertTrue(sawWaitNote.get(), "the status says why it waits");
        assertEquals(List.of(BUTTON), sim.bot.buttonPresses, "one press");
        assertTrue(sim.bot.buttonPressTicks.getFirst() >= popAt[0], "pressed after it popped");
        assertEquals(1, sim.bot.count("pressButton"), "no press task while it was pressed");
        assertTrue(job.tiles.getFirst().counted);
    }

    @Test
    void aButtonThatStaysPressedIsOneAlertAndTheJobGoesOn() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(true)); // held down (or re-pressed) all the time
        Job job = single(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(), sim.bot.buttonPresses);
        List<SimFakes.RecordingNotifier.Msg> alerts = counterAlerts(sim);
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("The map counter was not pressed: the button at " + BUTTON.key()
            + " stayed pressed for 10 s"), alerts.getFirst().body());
        assertFalse(job.tiles.getFirst().counted);
    }

    @Test
    void aFailedPressIsOneAlertAndTheJobGoesOn() {
        Sim sim = sim(new RecordingLogger());
        sim.world.set(BUTTON, oakButton(false));
        sim.bot.failNext.put("pressButton", 1);
        Job job = pair(sim);
        List<Phase> seen = new ArrayList<>();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            if (seen.isEmpty() || seen.getLast() != sim.phase()) seen.add(sim.phase());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());

        List<SimFakes.RecordingNotifier.Msg> alerts = counterAlerts(sim);
        assertEquals(1, alerts.size(), sim.notifier.toString());
        String body = alerts.getFirst().body();
        assertTrue(body.contains("The map counter was not pressed: ") && body.contains("injected failure"), body);
        assertTrue(body.contains("The map is stored and the bot carries on."), body);
        assertFalse(seen.contains(Phase.BLOCKED), seen.toString());
        assertFalse(job.tiles.get(0).counted, "the failed one");
        assertTrue(job.tiles.get(1).counted, "the next map is counted as usual");
        assertEquals(List.of(BUTTON), sim.bot.buttonPresses);
        assertNotEquals(JobStatus.FAILED, job.status);
    }

    @Test
    void theExampleSitePressesTheOakButtonOneWestOfTheColumn() throws java.io.IOException {
        // testserver/examples/site-config.moss.json, base-relative: the owner confirmed the counter is the button in the
        // download one west of the column first given (x 50, z -70), so the site has an exact pos and no column search
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("testserver/examples/site-config.moss.json"));
        MapArtConfig c = new com.google.gson.Gson().fromJson("{\"site\":" + json + "}", MapArtConfig.class);
        assertTrue(MapCounter.enabled(c.site));
        assertNull(MapCounter.configProblem(c.site));
        assertEquals(new Pos(49, 175, -70), c.site.mapCounter.pos);
        assertNull(c.site.mapCounterColumn, "no column search with an exact pos");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{169, 181}, MapCounter.scanRange(c.site));
        assertEquals(10, c.site.mapCounterLoginDelaySeconds, "the default login wait");
        assertEquals(4, c.site.mapCounterCheckRadius, "the default jam check");
    }

    @Test
    void aMisconfiguredCounterIsOneAlert() {
        Sim sim = sim(new RecordingLogger());
        sim.config.site.mapCounterColumn = null; // and no pos
        Job job = pair(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        List<SimFakes.RecordingNotifier.Msg> alerts = counterAlerts(sim);
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("site.mapCounter has no pos and site.mapCounterColumn is not set"),
            alerts.getFirst().body());
    }
}
