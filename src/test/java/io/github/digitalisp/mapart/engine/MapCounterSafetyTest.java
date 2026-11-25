package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map counter's safety rules (2026-10-11 local test: a press 0.3 s after a login jammed the owner's counter twice):
 * COUNT presses only once the bot has been in game, and the counter's chunk loaded, for
 * {@code site.mapCounterLoginDelaySeconds} of real time; and the jam rules around each press
 * ({@code site.mapCounterCheckRadius}): signal properties ignored, a stuck piston or a block out of place is one alert per
 * episode, the counts of later maps are owed and pressed by themselves once the counter checks clean, and a changed but
 * unstuck counter is taken as the new normal after 3 maps. No {@code resume} is needed. Synthetic coordinates beside the
 * Sim's supply chests; the fake counter copies the shape of the owner's (offsets from the button as in the owner's world download).
 */
class MapCounterSafetyTest {
    private static final int LONG = 60_000;
    private static final int CX = -55;
    private static final int CZ = 101;
    private static final Pos BUTTON = new Pos(CX, 62, CZ);
    /** The mechanism: a sticky piston behind the button that pushes a redstone block one cell north while powered. */
    private static final Pos PISTON = BUTTON.offset(0, 0, -1);
    private static final Pos BLOCK = BUTTON.offset(0, 0, -2);
    private static final Pos PUSHED = BUTTON.offset(0, 0, -3);
    /** The display: a lamp and a wire that change with every count, as in the owner's counter. */
    private static final Pos LAMP = BUTTON.offset(-3, 2, -2);
    private static final Pos WIRE = BUTTON.offset(-3, 3, -4);
    /** The bot's anvil tower stands within the cube, as at the owner's base. */
    private static final Pos ANVIL_TOP = BUTTON.offset(3, 3, -4);
    private static final String JAM = BuildEngine.COUNTER_JAM_TITLE;
    private static final String OK = BuildEngine.COUNTER_OK_TITLE;

    private static Sim sim() {
        return sim(new RecordingLogger());
    }

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.site.mapCounter = new MapArtConfig.ActuatorCfg();
        sim.config.site.mapCounterColumn = new MapArtConfig.ColumnCfg(CX, CZ);
        sim.world.set(BUTTON, oakButton(false));
        sim.world.set(PISTON, piston(false));
        sim.world.set(BLOCK, BlockSpec.of("minecraft:redstone_block"));
        sim.world.set(LAMP, lamp(false));
        sim.world.set(WIRE, wire(0));
        sim.world.set(ANVIL_TOP, BlockSpec.of("minecraft:anvil", Map.of("facing", "north")));
        return sim;
    }

    private static BlockSpec oakButton(boolean powered) {
        return BlockSpec.of("minecraft:oak_button", Map.of("face", "wall", "facing", "south", "powered",
            String.valueOf(powered)));
    }

    private static BlockSpec piston(boolean extended) {
        return BlockSpec.of("minecraft:sticky_piston", Map.of("facing", "north", "extended", String.valueOf(extended)));
    }

    private static BlockSpec lamp(boolean lit) {
        return BlockSpec.of("minecraft:redstone_lamp", Map.of("lit", String.valueOf(lit)));
    }

    private static BlockSpec wire(int power) {
        return BlockSpec.of("minecraft:redstone_wire", Map.of("east", "none", "north", "side", "power",
            String.valueOf(power), "south", "side", "west", "none"));
    }

    /** What one press does to the fake counter's mechanism. */
    private enum Press {
        /** Out and back: the three cells are as before 31 ticks later (after the wooden button popped). */
        CLEAN,
        /** The piston stays out with the block pushed (the owner's counter "left permanently powered"). */
        STUCK,
        /** The piston comes back without its block (a sticky piston dropped it): no piston stuck out. */
        DROP
    }

    /** The fake counter: each press follows the next entry of {@code plan} (CLEAN once it runs out) and counts. */
    private static final class FakeCounter {
        final Sim sim;
        final LinkedList<Press> plan = new LinkedList<>();
        final List<Long> pressedAt = new ArrayList<>();
        int count;

        FakeCounter(Sim sim, Press... plan) {
            this.sim = sim;
            this.plan.addAll(List.of(plan));
            sim.bot.useHandlers.put(BUTTON, this::press);
        }

        private void press() {
            pressedAt.add(sim.clock.millis());
            Press p = plan.isEmpty() ? Press.CLEAN : plan.removeFirst();
            count++;
            sim.world.set(LAMP, lamp(count % 2 == 1)); // the display shows the new count
            sim.world.set(WIRE, wire(count % 16));
            BlockSpec piston = sim.world.block(PISTON), block = sim.world.block(BLOCK), pushed = sim.world.block(PUSHED);
            sim.world.set(PISTON, piston(true));
            sim.world.set(PUSHED, block);
            sim.world.set(BLOCK, BlockSpec.AIR);
            if (p == Press.CLEAN) {
                sim.bot.schedule(31, () -> {
                    sim.world.set(PISTON, piston);
                    sim.world.set(BLOCK, block);
                    sim.world.set(PUSHED, pushed);
                });
            } else if (p == Press.DROP) {
                sim.bot.schedule(31, () -> sim.world.set(PISTON, piston(false)));
            }
        }

        /** Put the mechanism back as built (the owner fixed it). */
        void fix() {
            sim.world.set(PISTON, piston(false));
            sim.world.set(BLOCK, BlockSpec.of("minecraft:redstone_block"));
            sim.world.set(PUSHED, BlockSpec.AIR);
        }
    }

    private static Job job(Sim sim, int maps) {
        return sim.addJob("j06" + maps, "Row" + maps, maps, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
    }

    private static List<SimFakes.RecordingNotifier.Msg> alerts(Sim sim, String title) {
        return sim.notifier.of("alert").stream().filter(m -> m.title().equals(title)).toList();
    }

    private static List<SimFakes.RecordingNotifier.Msg> infos(Sim sim, String title) {
        return sim.notifier.of("info").stream().filter(m -> m.title().equals(title)).toList();
    }

    /** A reconnect as ZenithProxy reports it: bot ticks stop, the client disconnects, then the bot ticks start again. */
    private static long reconnect(Sim sim) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.engine.onBotTickStarting();
        return sim.clock.millis();
    }

    private static String loginNote(int secs) {
        return String.format(java.util.Locale.ROOT, BuildEngine.COUNTER_LOGIN_NOTE, secs);
    }

    /** True once the COUNT of map {@code i} is over (the map is stored and the engine moved on to its clean-up). */
    private static boolean countOver(Sim sim, Job job, int i) {
        Job.TileState t = job.tiles.get(i);
        return t.status == TileStatus.STORED && sim.phase() != Phase.COUNT && sim.phase() != Phase.STORE
            && sim.engine.status().tileX() == t.x;
    }

    // ---------------------------------------------------------------- the login wait

    @Test
    void noPressUntilTheDelayAfterAReconnectThenOnePress() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.COUNT, LONG), sim.state());
        long login = reconnect(sim);

        List<String> notes = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            notes.add(sim.engine.status().note());
            return !counter.pressedAt.isEmpty();
        }, LONG), sim.state());
        long at = counter.pressedAt.getFirst();
        assertTrue(at >= login + 10_000, "pressed " + (at - login) + " ms after the login");
        assertTrue(at <= login + 11_000, "pressed once the wait was over: " + (at - login) + " ms");
        // counted down again from the reconnect (the first steps come after START_DELAY_TICKS, 2 s)
        assertTrue(notes.contains(loginNote(8)), notes.toString());
        assertTrue(notes.contains(loginNote(1)), notes.toString());

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(BUTTON), sim.bot.buttonPresses, "one press");
        assertTrue(job.tiles.getFirst().counted);
        assertEquals(List.of(), alerts(sim, JAM), "the mechanism went back");
    }

    @Test
    void aPressComesAtOnceWhenTheBotHasBeenInGameLongEnough() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim);
        sim.start();
        sim.run(400); // 20 s in game, idle, before the job is queued
        Job job = job(sim, 1);
        sim.engine.queueChanged();
        long[] countAt = {-1};
        List<String> notes = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            if (countAt[0] < 0 && sim.phase() == Phase.COUNT) countAt[0] = sim.clock.millis();
            notes.add(sim.engine.status().note());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, counter.pressedAt.size());
        assertTrue(counter.pressedAt.getFirst() - countAt[0] <= 200, "no wait: pressed "
            + (counter.pressedAt.getFirst() - countAt[0]) + " ms after COUNT began");
        assertFalse(notes.stream().anyMatch(n -> n.endsWith(" s after login")), "no login wait");
        assertTrue(job.tiles.getFirst().counted);
    }

    @Test
    void theDelayCountsRealTimeNotTicks() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.COUNT, LONG), sim.state());
        sim.msPerTick = 5; // the local test server at 10x: 200 ticks are 1 s
        long login = reconnect(sim);
        long ticks = sim.ticks;

        sim.run(1_000); // far more than START_DELAY_TICKS and 10 s worth of ticks at 20/s, but 5 s of real time
        assertEquals(List.of(), counter.pressedAt, "not pressed 5 s after the login");
        String note = sim.engine.status().note(); // polled every 5 ticks: 5 s left, or a hair more
        assertTrue(note.equals(loginNote(5)) || note.equals(loginNote(6)), note);

        assertTrue(sim.runUntil(() -> !counter.pressedAt.isEmpty(), LONG), sim.state());
        assertTrue(counter.pressedAt.getFirst() >= login + 10_000, (counter.pressedAt.getFirst() - login) + " ms");
        assertTrue(sim.ticks - ticks >= 2_000, (sim.ticks - ticks) + " ticks");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG * 5), sim.state());
        assertEquals(1, sim.bot.buttonPresses.size());
    }

    @Test
    void theCounterChunkMustHaveBeenLoadedForTheDelayToo() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.COUNT, LONG), sim.state());
        long login = reconnect(sim);
        long loadAt = login + 4_000;
        int cx = BUTTON.x() >> 4, cz = BUTTON.z() >> 4;
        sim.world.loaded = p -> (p.x() >> 4) != cx || (p.z() >> 4) != cz || sim.clock.millis() >= loadAt;

        assertTrue(sim.runUntil(() -> !counter.pressedAt.isEmpty(), LONG), sim.state());
        long at = counter.pressedAt.getFirst();
        assertTrue(at >= loadAt + 10_000, "pressed " + (at - loadAt) + " ms after the chunk loaded");
        assertTrue(at <= loadAt + 11_000, "pressed " + (at - loadAt) + " ms after the chunk loaded");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.buttonPresses.size());
        assertTrue(job.tiles.getFirst().counted);
    }

    @Test
    void theWaitDoesNotHoldBackEatingOrAPause() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.COUNT, LONG), sim.state());
        reconnect(sim);
        assertTrue(sim.runUntil(() -> sim.engine.status().note().endsWith(" s after login"), LONG), sim.state());

        int eats = sim.bot.eats;
        sim.inv.foodLevel = 10; // below eatFoodLevel 14
        assertTrue(sim.runUntil(() -> sim.bot.eats > eats, 100), "eats during the wait: " + sim.state());
        assertEquals(List.of(), counter.pressedAt);

        sim.engine.pause("test");
        sim.run(400); // 20 s: the wait is over, but paused
        assertEquals(List.of(), counter.pressedAt, "no press while paused");
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, counter.pressedAt.size());
    }

    // ---------------------------------------------------------------- the jam rules

    @Test
    void aCleanPressWithTheDisplayChangingIsNoAlert() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 2);
        List<String> notes = new ArrayList<>();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            notes.add(sim.engine.status().note());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());

        assertEquals(List.of(BUTTON, BUTTON), sim.bot.buttonPresses);
        assertEquals(List.of(), alerts(sim, JAM), sim.notifier.toString());
        assertEquals(List.of(), infos(sim, OK));
        assertTrue(notes.contains(BuildEngine.COUNTER_CHECK_NOTE), "it checked");
        for (Job.TileState t : job.tiles) assertTrue(t.counted);
        assertEquals(wire(2), sim.world.block(WIRE), "the display shows 2");
        // each check saw the lamp and the wire change, and called it clean
        assertEquals(2, log.lines.stream().filter(l -> l.contains("back to its start state")
            && l.contains("2 changed only in signal properties")).count(), String.join("\n", log.lines));
    }

    @Test
    void theJamCheckIgnoresSignalPropertiesAndAnvilWearButNotPistonsOrPlaces() {
        BlockSpec chipped = BlockSpec.of("minecraft:chipped_anvil", Map.of("facing", "north"));
        BlockSpec anvil = BlockSpec.of("minecraft:anvil", Map.of("facing", "north"));
        assertEquals(MapCounter.normalize(anvil), MapCounter.normalize(chipped));
        assertEquals(MapCounter.normalize(lamp(true)), MapCounter.normalize(lamp(false)));
        assertEquals(MapCounter.normalize(wire(12)), MapCounter.normalize(wire(13)));
        for (String k : List.of("powered", "triggered", "enabled", "locked")) {
            assertEquals(MapCounter.normalize(BlockSpec.of("minecraft:hopper", Map.of(k, "true", "facing", "down"))),
                MapCounter.normalize(BlockSpec.of("minecraft:hopper", Map.of(k, "false", "facing", "down"))), k);
        }
        assertNotEquals(MapCounter.normalize(piston(true)), MapCounter.normalize(piston(false)), "extended counts");
        assertNotEquals(MapCounter.normalize(piston(false)), MapCounter.normalize(BlockSpec.of("minecraft:sticky_piston",
            Map.of("facing", "west", "extended", "false"))), "facing counts");
        assertNotEquals(MapCounter.normalize(BlockSpec.AIR), MapCounter.normalize(BlockSpec.of("minecraft:redstone_block")));

        assertTrue(MapCounter.stuck(piston(true)));
        assertFalse(MapCounter.stuck(piston(false)));
        assertTrue(MapCounter.stuck(BlockSpec.of("minecraft:piston", Map.of("facing", "up", "extended", "true"))));
        assertTrue(MapCounter.stuck(BlockSpec.of("minecraft:piston_head", Map.of("facing", "up", "short", "false",
            "type", "sticky"))));
        assertTrue(MapCounter.stuck(BlockSpec.of("minecraft:moving_piston", Map.of("facing", "up", "type", "normal"))));
        assertFalse(MapCounter.stuck(BlockSpec.of("minecraft:redstone_block")));
    }

    @Test
    void aStuckPistonIsOneAlertTheNextCountIsOwedAndPressedOnceItIsFixed() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        FakeCounter counter = new FakeCounter(sim, Press.STUCK);
        Job job = job(sim, 3);
        sim.start();
        long[] alertAt = {-1};
        assertTrue(sim.runUntil(() -> {
            if (alertAt[0] < 0 && !alerts(sim, JAM).isEmpty()) alertAt[0] = sim.clock.millis();
            return countOver(sim, job, 1);
        }, LONG), sim.state());

        // map 0: pressed, and the check after it found the piston out
        List<SimFakes.RecordingNotifier.Msg> jams = alerts(sim, JAM);
        assertEquals(1, jams.size(), sim.notifier.toString());
        String body = jams.getFirst().body();
        // the first changed cell in x, y, z order: the block the piston pushed north
        assertTrue(body.contains("Map counter may be jammed: 3 blocks around the counter did not return to their start"
            + " state (e.g. redstone block at " + PUSHED.key() + ", was air). Check it in game."), body);
        assertTrue(body.contains("their counts are kept and pressed by themselves once it checks clean again"), body);
        assertFalse(body.contains("resume"), "nothing needs a resume: " + body);
        assertTrue(alertAt[0] - counter.pressedAt.getFirst() >= 2_500, "after the pop (1.5 s) and 1 s more: "
            + (alertAt[0] - counter.pressedAt.getFirst()) + " ms");
        assertTrue(job.tiles.get(0).counted, "the press counted");
        // map 1: the piston is still out: not pressed, its count owed, no second alert
        assertEquals(1, counter.pressedAt.size(), "not pressed while a piston is stuck out");
        assertFalse(job.tiles.get(1).counted);
        assertEquals(1, log.lines.stream().filter(l -> l.contains("map counter not pressed for")
            && l.contains("a piston is stuck out before the press") && l.contains("1 count owed")).count(),
            String.join("\n", log.lines));

        counter.fix(); // the owner puts the piston back
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(3, counter.pressedAt.size(), "map 2: the owed count, then its own");
        assertTrue(counter.pressedAt.get(2) - counter.pressedAt.get(1) >= 2_500, "pop and settle between them");
        assertTrue(job.tiles.get(2).counted);
        assertEquals(1, alerts(sim, JAM).size(), "one alert for the episode");
        List<SimFakes.RecordingNotifier.Msg> ok = infos(sim, OK);
        assertEquals(1, ok.size(), sim.notifier.toString());
        assertTrue(ok.getFirst().body().contains("Map counter working again: 1 owed count pressed."),
            ok.getFirst().body());
        assertEquals(List.of(), alerts(sim, "Map counter not pressed"));
    }

    @Test
    void aJamWhileCatchingUpIsTheSameEpisodeAndAJamAfterItIsANewOne() {
        Sim sim = sim();
        // map 0 jams; map 1 owed; map 2's owed press jams again; map 3 catches up; map 4 jams anew
        FakeCounter counter = new FakeCounter(sim, Press.STUCK, Press.STUCK, Press.CLEAN, Press.CLEAN, Press.STUCK);
        Job job = job(sim, 5);
        sim.start();
        assertTrue(sim.runUntil(() -> countOver(sim, job, 1), LONG), sim.state());
        counter.fix();
        assertTrue(sim.runUntil(() -> countOver(sim, job, 2), LONG), sim.state());
        assertEquals(2, counter.pressedAt.size(), "map 2 pressed the owed count only");
        assertEquals(1, alerts(sim, JAM).size(), "still the first episode: " + sim.notifier);
        assertFalse(job.tiles.get(2).counted, "its own count owed in turn");
        counter.fix();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG * 2), sim.state());

        assertEquals(5, counter.pressedAt.size());
        List<SimFakes.RecordingNotifier.Msg> ok = infos(sim, OK);
        assertEquals(1, ok.size(), sim.notifier.toString());
        assertTrue(ok.getFirst().body().contains("2 owed counts pressed"), ok.getFirst().body());
        assertEquals(2, alerts(sim, JAM).size(), "map 4's jam is a new episode: " + sim.notifier);
        assertTrue(job.tiles.get(3).counted);
        assertTrue(job.tiles.get(4).counted);
    }

    @Test
    void aBlockOutOfPlaceWithNoPistonOutIsTakenAsTheNewNormalAfterThreeMaps() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim, Press.DROP);
        Job job = job(sim, 4);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG * 2), sim.state());

        // map 0 dropped the block (alert); maps 1 and 2 owed; map 3 takes the state as normal and presses 2 + 1
        assertEquals(4, counter.pressedAt.size());
        assertEquals(1, alerts(sim, JAM).size(), sim.notifier.toString());
        assertTrue(alerts(sim, JAM).getFirst().body().contains("2 blocks around the counter did not return"),
            alerts(sim, JAM).getFirst().body());
        assertTrue(job.tiles.get(0).counted);
        assertFalse(job.tiles.get(1).counted);
        assertFalse(job.tiles.get(2).counted);
        assertTrue(job.tiles.get(3).counted);
        List<SimFakes.RecordingNotifier.Msg> ok = infos(sim, OK);
        assertEquals(1, ok.size(), sim.notifier.toString());
        String body = ok.getFirst().body();
        assertTrue(body.contains("Map counter working again: 2 owed counts pressed."), body);
        assertTrue(body.contains("now taken as its normal one"), body);
        assertEquals(BlockSpec.AIR, sim.world.block(BLOCK), "nothing is fixed by the bot");
    }

    @Test
    void aPistonOnItsWayBackIsNotAJam() {
        Sim sim = sim();
        sim.config.site.mapCounterLoginDelaySeconds = 0;
        FakeCounter counter = new FakeCounter(sim);
        Job job = job(sim, 1);
        boolean[] pressed = {false};
        sim.start();
        // someone presses the counter as the map is stored: button and piston are still out when COUNT starts
        assertTrue(sim.runUntil(() -> {
            if (!pressed[0] && sim.phase() == Phase.STORE) {
                pressed[0] = true;
                sim.world.set(BUTTON, oakButton(true));
                sim.world.set(PISTON, piston(true));
                sim.bot.schedule(30, () -> sim.world.set(BUTTON, oakButton(false)));
                sim.bot.schedule(36, () -> sim.world.set(PISTON, piston(false)));
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, counter.pressedAt.size(), "pressed once the piston was back");
        assertEquals(List.of(), alerts(sim, JAM), sim.notifier.toString());
        assertTrue(job.tiles.getFirst().counted);
    }

    @Test
    void resumeTakesTheCounterAsItIsAndKeepsTheOwedCounts() {
        Sim sim = sim();
        FakeCounter counter = new FakeCounter(sim, Press.DROP);
        Job job = job(sim, 3);
        sim.start();
        assertTrue(sim.runUntil(() -> countOver(sim, job, 1), LONG), sim.state());
        assertEquals(1, counter.pressedAt.size(), "map 1 owed");
        sim.engine.resume(); // not needed, but harmless: the next map presses at once instead of after 3 maps
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(3, counter.pressedAt.size(), "map 2: the owed count, then its own");
        assertEquals(1, alerts(sim, JAM).size());
        assertTrue(infos(sim, OK).getFirst().body().contains("1 owed count pressed"), sim.notifier.toString());
    }

    @Test
    void radiusZeroChecksNothing() {
        Sim sim = sim();
        sim.config.site.mapCounterCheckRadius = 0;
        FakeCounter counter = new FakeCounter(sim, Press.STUCK);
        Job job = job(sim, 2);
        List<String> notes = new ArrayList<>();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            notes.add(sim.engine.status().note());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(2, counter.pressedAt.size(), "pressed for both maps");
        assertEquals(List.of(), alerts(sim, JAM));
        assertFalse(notes.contains(BuildEngine.COUNTER_CHECK_NOTE));
        for (Job.TileState t : job.tiles) assertTrue(t.counted);
    }

    @Test
    void theSnapshotSkipsUnloadedBlocks() {
        SimFakes.FakeWorld w = new SimFakes.FakeWorld();
        Pos centre = new Pos(5, 64, 5);
        w.set(centre, oakButton(false));
        w.set(centre.above(), BlockSpec.of("minecraft:redstone_block"));
        w.set(centre.offset(1, 0, 0), BlockSpec.of("minecraft:stone"));
        w.loaded = p -> p.x() != centre.x() + 1; // one slice not loaded (another chunk)
        Map<Pos, BlockSpec> before = MapCounter.snapshot(w, centre, 1);
        assertEquals(27 - 9, before.size());
        assertFalse(before.containsKey(centre.offset(1, 0, 0)));
        assertEquals(List.of(), MapCounter.differs(before, MapCounter.snapshot(w, before)));

        w.set(centre.above(), BlockSpec.AIR);
        w.loaded = p -> p.y() != centre.y() + 1 && p.x() != centre.x() + 1; // the changed cell unloaded now
        assertEquals(List.of(), MapCounter.differs(before, MapCounter.snapshot(w, before)), "unloaded now: skipped");
        w.loaded = p -> p.x() != centre.x() + 1;
        assertEquals(List.of(centre.above()), MapCounter.differs(before, MapCounter.snapshot(w, before)));
        assertEquals(0, MapCounter.checkRadius(radius(-1)));
        assertEquals(MapCounter.MAX_CHECK_RADIUS, MapCounter.checkRadius(radius(100)));
        assertEquals(4, new MapArtConfig.SiteCfg().mapCounterCheckRadius, "the default covers the owner's three pistons");
    }

    private static MapArtConfig.SiteCfg radius(int r) {
        MapArtConfig.SiteCfg s = new MapArtConfig.SiteCfg();
        s.mapCounterCheckRadius = r;
        return s;
    }
}
