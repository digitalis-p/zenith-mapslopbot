package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Moss item collection run-on: after the art layer reads clear the carts keep running (items pushed off by the pistons
 * are still in transit) until no dropped item has been seen on the platform for {@code collectionQuietSeconds}, at most
 * {@code collectionRunAfterClearSeconds}, then OFF follows before the next build. Without an item count (no entity
 * data, the default in the Sim) the cap is a fixed run-on.
 */
class CollectionRunOnTest {
    private static final int LONG = 60_000;
    private static final String OFF = "use " + Sim.MOSS_CARTS_OFF;

    private static void assertDone(Sim sim, Job job, Recorder rec) {
        assertTrue(sim.runUntil(() -> rec.observe() && job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.runUntil(() -> rec.observe() && sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    /** One WAIT_CLEAR of the moss platform as seen from outside. */
    private static final class Wait {
        int historyAtEntry;
        /** ms the art layer became air (the fake flying machines ran); -1 = never in this WAIT_CLEAR. */
        long clearedAt = -1;
        int historyAtClear = -1;
        /** History size at the last observation still in WAIT_CLEAR. */
        int historyInWait;
        /** ms the engine left WAIT_CLEAR. */
        long leftAt = -1;
        boolean cartsOnWhenLeft;
        boolean cartsOffDuringRunOn;
        final List<String> notes = new ArrayList<>();

        long runOn() {
            return leftAt - clearedAt;
        }
    }

    /** Watches every tick (call {@link #observe()} from the run condition) and records each WAIT_CLEAR. */
    private static final class Recorder {
        final Sim sim;
        final List<Wait> waits = new ArrayList<>();
        Phase last;
        Wait cur;

        Recorder(Sim sim) {
            this.sim = sim;
        }

        boolean observe() {
            Phase now = sim.phase();
            if (now == Phase.WAIT_CLEAR || last == Phase.WAIT_CLEAR) {
                if (cur == null) {
                    cur = new Wait();
                    cur.historyAtEntry = sim.bot.history.size();
                    waits.add(cur);
                }
                if (cur.clearedAt < 0 && sim.artBlocks(sim.moss) == 0) {
                    cur.clearedAt = sim.clock.millis();
                    cur.historyAtClear = sim.bot.history.size();
                }
                if (cur.clearedAt >= 0 && !sim.cartsOn()) cur.cartsOffDuringRunOn = true;
                if (now == Phase.WAIT_CLEAR) {
                    cur.historyInWait = sim.bot.history.size();
                    String note = sim.engine.status().note();
                    if (note.startsWith(BuildEngine.COLLECTING_NOTE)) cur.notes.add(note);
                } else {
                    cur.leftAt = sim.clock.millis();
                    cur.cartsOnWhenLeft = sim.cartsOn();
                    cur = null;
                }
            }
            last = now;
            return true;
        }

        List<Wait> cleared() {
            return waits.stream().filter(w -> w.clearedAt >= 0).toList();
        }
    }

    /** No OFF press while WAIT_CLEAR ran; the next OFF comes before the next lane is placed. */
    private static void assertOffAfterRunOnBeforeNextLane(Sim sim, Wait w) {
        List<String> h = sim.bot.history;
        assertFalse(h.subList(w.historyAtEntry, w.historyInWait).contains(OFF), "carts switched off during WAIT_CLEAR: " + h);
        int off = -1;
        int lane = -1;
        for (int i = w.historyInWait; i < h.size(); i++) {
            if (off < 0 && h.get(i).equals(OFF)) off = i;
            if (lane < 0 && h.get(i).startsWith("placeLane")) lane = i;
        }
        assertTrue(off >= 0, "carts switched off after the run-on: " + h);
        if (lane >= 0) assertTrue(off < lane, "carts off before the next build: " + h);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void cartsStayOnForTheConfiguredTimeThenGoOffBeforeTheNextTile() {
        Sim sim = new Sim();
        sim.moss.collectionRunAfterClearSeconds = 25; // longer than the clear timeout (10 s in Sim)
        Recorder rec = new Recorder(sim);
        Job first = sim.addJob("j0201", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        Job second = sim.addJob("j0202", "Second", 1, 1, List.of("moss"), (x, z) -> Sim.single(RED, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> rec.observe() && first.status == JobStatus.DONE, LONG), sim.state());
        assertDone(sim, second, rec);

        List<Wait> cleared = rec.cleared();
        assertEquals(2, cleared.size(), "one clear per map");
        Wait w = cleared.getFirst();
        assertTrue(w.runOn() >= 25_000 && w.runOn() <= 27_000, "ran on " + w.runOn() + " ms after the clear");
        assertFalse(w.cartsOffDuringRunOn, "carts on during the run-on");
        assertTrue(w.cartsOnWhenLeft, "carts still on when WAIT_CLEAR ended");
        assertEquals("carts collecting, 25 s left", w.notes.getFirst());
        assertEquals("carts collecting, 1 s left", w.notes.getLast());
        assertTrue(sim.bot.history.subList(w.historyAtClear, w.historyInWait).stream().noneMatch(s -> s.startsWith("walkTo")),
            "stayed put during the run-on: " + sim.bot.history);
        assertOffAfterRunOnBeforeNextLane(sim, w);
        assertOffAfterRunOnBeforeNextLane(sim, cleared.getLast());

        assertEquals(2, sim.bot.count("use " + Sim.MOSS_BUTTON), "one press per clear: the run-on is not a clear timeout");
        assertFalse(sim.notifier.has("info", "Clear timed out"), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertFalse(sim.bot.placedWithToggleOn, "nothing built with the carts on");
        assertFalse(sim.cartsOn(), "carts off at the end");
        assertEquals(2, sim.output().maps.size());
    }

    @Test
    void zeroRunOnLeavesAsSoonAsTheLayerIsClear() {
        Sim sim = new Sim();
        sim.moss.collectionRunAfterClearSeconds = 0;
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob("j0203", "Zero", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job, rec);
        Wait w = rec.cleared().getFirst();
        assertTrue(w.runOn() < 1_500, "left " + w.runOn() + " ms after the clear");
        assertTrue(w.notes.isEmpty(), w.notes.toString());
        assertOffAfterRunOnBeforeNextLane(sim, w);
        assertEquals(1, sim.bot.count(OFF));
        assertFalse(sim.cartsOn());
    }

    @Test
    void noCollectionConfiguredLeavesAsSoonAsTheLayerIsClear() {
        Sim sim = new Sim();
        sim.moss.collectionOn = null;
        sim.moss.collectionOff = null;
        sim.moss.collectionIndicator = null;
        sim.bot.collectionLamp = null;
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob("j0204", "NoCarts", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job, rec);
        Wait w = rec.cleared().getFirst();
        assertTrue(w.runOn() < 1_500, "left " + w.runOn() + " ms after the clear");
        assertTrue(w.notes.isEmpty(), w.notes.toString());
        assertEquals(0, sim.bot.count("use " + Sim.MOSS_CARTS_ON) + sim.bot.count(OFF));
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void clearTimeoutStillAppliesToTheClearItself() {
        Sim sim = new Sim();
        sim.clearWorks = false;
        AtomicInteger presses = new AtomicInteger();
        Runnable press = sim.bot.useHandlers.get(Sim.MOSS_BUTTON);
        sim.bot.useHandlers.put(Sim.MOSS_BUTTON, () -> {
            if (presses.incrementAndGet() == 2) sim.clearWorks = true; // the retry works
            press.run();
        });
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob("j0205", "Retry", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job, rec);

        assertEquals(2, presses.get(), "pressed again after the 10 s timeout");
        assertEquals(1, sim.notifier.of("info").stream().filter(m -> m.title().equals("Clear timed out")).count(),
            "one timeout (the clear), none during the 30 s run-on: " + sim.notifier);
        assertEquals(2, rec.waits.size(), rec.waits.size() + " WAIT_CLEARs");
        Wait timedOut = rec.waits.getFirst();
        assertEquals(-1, timedOut.clearedAt);
        assertTrue(timedOut.notes.isEmpty(), "no run-on without a clear");
        Wait w = rec.waits.getLast();
        assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "default run-on after the retried clear: " + w.runOn());
        assertFalse(w.cartsOffDuringRunOn);
        assertOffAfterRunOnBeforeNextLane(sim, w);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void multiMapJobRunsOnAfterEveryClear() {
        Sim sim = new Sim();
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob("j0206", "Three", 3, 1, List.of("moss"), (x, z) -> Sim.single(x == 1 ? RED : WHITE, 2));
        sim.start();
        assertDone(sim, job, rec);
        List<Wait> cleared = rec.cleared();
        assertEquals(3, cleared.size(), "one clear per map");
        for (Wait w : cleared) {
            assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "ran on " + w.runOn() + " ms (default 30 s)");
            assertFalse(w.cartsOffDuringRunOn);
            assertTrue(w.cartsOnWhenLeft);
            assertOffAfterRunOnBeforeNextLane(sim, w);
        }
        assertEquals(3, sim.bot.count("use " + Sim.MOSS_CARTS_ON), "ON at every NUKE");
        assertEquals(3, sim.bot.count(OFF), "OFF at every FINISH_TILE");
        assertFalse(sim.bot.placedWithToggleOn);
        assertEquals(3, sim.output().maps.size());
    }

    @Test
    void restartDuringTheRunOnStartsItOver() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0207", "Restart", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> "carts collecting, 20 s left".equals(sim.engine.status().note()), LONG), sim.state());
        assertEquals("WAIT_CLEAR", sim.store.checkpoint.phase);
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        sim.newEngine();
        long restartAt = sim.clock.millis();
        sim.start();
        AtomicBoolean cartsOff = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, 200), sim.state());
        assertTrue(sim.runUntil(() -> {
            if (!sim.cartsOn()) cartsOff.set(true);
            return sim.phase() != Phase.WAIT_CLEAR;
        }, 2_000), sim.state());
        long ranOn = sim.clock.millis() - restartAt;
        assertTrue(ranOn >= 30_000 && ranOn <= 35_000, "run-on started over after the restart: " + ranOn + " ms");
        assertFalse(cartsOff.get(), "carts on until the run-on ended");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.output().maps.size());
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_BUTTON), "no second clear");
        assertFalse(sim.cartsOn());
    }

    // --- item count: off once no dropped items are left ------------------------------------------

    /** A Sim whose bot counts the dropped items: entity data on, at the moss centre during WAIT_CLEAR (as live). */
    private static Sim counting(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.moss.stayDuringClear = true;
        sim.entityData(true);
        return sim;
    }

    private static boolean logged(RecordingLogger log, String regex) {
        return log.at(Level.INFO).stream().anyMatch(l -> l.matches(regex));
    }

    private static Wait runOneMap(Sim sim, String id) {
        Recorder rec = new Recorder(sim);
        Job job = sim.addJob(id, "Items", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertDone(sim, job, rec);
        List<Wait> cleared = rec.cleared();
        assertEquals(1, cleared.size(), "one clear");
        Wait w = cleared.getFirst();
        assertFalse(w.cartsOffDuringRunOn, "carts on during the run-on");
        assertTrue(w.cartsOnWhenLeft, "carts still on when WAIT_CLEAR ended");
        assertOffAfterRunOnBeforeNextLane(sim, w);
        assertEquals(1, sim.bot.count(OFF));
        assertFalse(sim.cartsOn(), "carts off at the end");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size());
        return w;
    }

    @Test
    void cartsGoOffAQuietPeriodAfterTheLastItemWasCollected() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.itemsPerClear = 20; // the carts take one every 10 ticks: all gone 10 s after the clear
        Wait w = runOneMap(sim, "j0208");
        assertTrue(w.runOn() >= 13_000 && w.runOn() <= 15_000, "10 s of items, then 3 s quiet: " + w.runOn() + " ms");
        assertEquals(0, sim.itemsOnMoss());
        assertTrue(w.notes.getFirst().matches("carts collecting, \\d+ items left, at most 30 s"), w.notes.toString());
        assertTrue(w.notes.contains("carts collecting, no items left, off in 1 s"), w.notes.toString());
        assertTrue(logged(log, "item collection on moss keeps running until no dropped items are left on the platform"
            + " for 3 s, at most 30 s \\(\\d+ now\\)"), log.lines.toString());
        assertTrue(logged(log, "collection: 0 items left after \\d+\\.\\d s; carts off after 1\\d\\.\\d s"),
            log.lines.toString());
    }

    /** Live, first map: the carts had everything when the art layer read clear; the fixed 30 s were all waiting. */
    @Test
    void noItemsLeftWhenTheLayerReadsClearGoesOffAfterTheQuietPeriod() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.world.viewDistance = 6; // 96 blocks: reaches the tile corners (91) from the centre
        Wait w = runOneMap(sim, "j0209");
        assertTrue(w.runOn() >= 3_000 && w.runOn() <= 4_500, "3 s quiet after the clear: " + w.runOn() + " ms");
        assertEquals("carts collecting, no items left, off in 3 s", w.notes.getFirst());
        assertTrue(logged(log, "collection: 0 items left after 0\\.0 s; carts off after 3\\.0 s"), log.lines.toString());
    }

    @Test
    void theCapEndsTheRunOnWhenItemsStay() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.itemsPerClear = 5;
        sim.cartTicksPerItem = 0; // the carts never get them
        Wait w = runOneMap(sim, "j0210");
        assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "the 30 s cap: " + w.runOn() + " ms");
        assertEquals("carts collecting, 5 items left, at most 30 s", w.notes.getFirst());
        assertEquals("carts collecting, 5 items left, at most 1 s", w.notes.getLast());
        assertTrue(logged(log, "collection: cap reached with 5 items left; carts off"), log.lines.toString());
        assertEquals(5, sim.itemsOnMoss());
    }

    @Test
    void itemsOffThePlatformVolumeDoNotHoldTheCarts() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        int x0 = sim.moss.minX, z0 = sim.moss.minZ, y = sim.moss.artY;
        sim.dropItem(x0 - 0.5, y + 0.1, z0 + 10.5);   // the drop channel just west of the tile
        sim.dropItem(x0 + 10.5, y + 0.1, z0 + 128.5); // past the south edge
        sim.dropItem(x0 + 10.5, y + 4.0, z0 + 10.5);  // above art + 3
        sim.dropItem(x0 + 10.5, y - 3.5, z0 + 10.5);  // below floor - 2
        Wait w = runOneMap(sim, "j0211");
        assertTrue(w.runOn() >= 3_000 && w.runOn() <= 4_500, "only the quiet period: " + w.runOn() + " ms");
        assertEquals(4, sim.world.items.size(), "the items off the platform are still there");
        assertTrue(logged(log, "collection: 0 items left after 0\\.0 s; carts off after 3\\.0 s"), log.lines.toString());
    }

    @Test
    void withoutEntityDataTheCapIsAFixedRunOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.entityData(false);
        Wait w = runOneMap(sim, "j0212");
        assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "fixed 30 s: " + w.runOn() + " ms");
        assertEquals("carts collecting, 30 s left", w.notes.getFirst());
        assertTrue(logged(log, "item collection on moss keeps running 30 s for items in transit \\(no item count: no"
            + " entity data for the platform\\)"), log.lines.toString());
        assertTrue(logged(log, "collection: fixed run-on of 30 s over \\(no item count\\); carts off"), log.lines.toString());
    }

    @Test
    void awayFromTheCentreTheItemsAreNotCounted() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.moss.stayDuringClear = false; // the bot stays by the clear button, 69 blocks from the centre
        Wait w = runOneMap(sim, "j0213");
        assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "fixed 30 s: " + w.runOn() + " ms");
        assertTrue(logged(log, ".*\\(no item count: the bot is not at the center\\)"), log.lines.toString());
    }

    @Test
    void aViewDistanceShortOfTheTileCornersFallsBackToTheFixedRunOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = counting(log);
        sim.world.viewDistance = 5; // entities are never tracked beyond 80 blocks; the corners are 91 away
        Wait w = runOneMap(sim, "j0214");
        assertTrue(w.runOn() >= 30_000 && w.runOn() <= 32_000, "fixed 30 s: " + w.runOn() + " ms");
        assertTrue(logged(log, ".*\\(no item count: the view distance \\(5 chunks, 80 blocks\\) does not reach the tile"
            + " corners \\(91 blocks\\)\\)"), log.lines.toString());
    }
}
