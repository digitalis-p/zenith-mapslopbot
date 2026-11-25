package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build.mapPaneAtStore}: the next tile's empty map and glass pane come
 * at STORE, one each, while the bot stands by their chests, and the next tile's last load makes no trip for them. Live
 * j0015 (soul sand, 2026-10-23): that trip to the station and back cost about 65 s a map. Off: as before.
 */
class MapPaneAtStoreTest {
    private static final int LONG = 120_000;
    private static final String MAP = "minecraft:map";
    private static final String PANE = "minecraft:glass_pane";
    private static final String XP = "minecraft:experience_bottle";

    private static Sim sim(RecordingLogger log, boolean on) {
        Sim sim = new Sim();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.mapPaneAtStore = on;
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    /** The phases the bot withdrew from {@code chest} in, in order. */
    private static List<Phase> withdrawals(Sim sim, Pos chest) {
        List<Phase> out = new ArrayList<>();
        for (var e : sim.bot.withdrawLog) if (e.getKey().equals(chest)) out.add(e.getValue());
        return out;
    }

    private static long lines(RecordingLogger log, Level level, String part) {
        return log.at(level).stream().filter(l -> l.contains(part)).count();
    }

    @Test
    void theNextTilesMapAndPaneComeAtStoreAndItsLastLoadMakesNoTripForThem() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = sim.addJob("j0501", "Ahead", 2, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        // the first tile's last load fetches them (no STORE before it), every STORE fetches the next tile's
        assertEquals(List.of(Phase.RESTOCK, Phase.STORE, Phase.STORE), withdrawals(sim, Sim.MAPS), sim.bot.history.toString());
        assertEquals(List.of(Phase.RESTOCK, Phase.STORE, Phase.STORE), withdrawals(sim, Sim.PANES), sim.bot.history.toString());
        assertEquals(2, lines(log, Level.INFO, "taking 1 " + MAP + " at "), tail(log));
        assertEquals(2, lines(log, Level.INFO, "taking 1 " + PANE + " at "), tail(log));
        // the second STORE's pair stays for a next tile; nothing else is left
        assertEquals(1, sim.inv.count(MAP));
        assertEquals(1, sim.inv.count(PANE));
        assertEquals(2, sim.output().maps.size());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void offEveryLastLoadFetchesThemAndStorePutsTheSparesBack() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false);
        sim.inv.add(PANE, 2); // a spare pane: STORE puts it back with the switch off
        Job job = sim.addJob("j0502", "Before", 2, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(List.of(Phase.RESTOCK, Phase.RESTOCK), withdrawals(sim, Sim.MAPS), sim.bot.history.toString());
        assertEquals(List.of(Phase.RESTOCK), withdrawals(sim, Sim.PANES), "the first tile used a carried pane: "
            + sim.bot.history);
        assertEquals(0, lines(log, Level.INFO, "for the next tile (build.mapPaneAtStore)"), tail(log));
        assertEquals(0, sim.inv.count(MAP));
        assertEquals(0, sim.inv.count(PANE));
        assertEquals(2, sim.output().maps.size());
    }

    @Test
    void storeKeepsOneOfEachAndPutsOnlyTheRestBackWithTheSwitchOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        sim.config.build.exactMapAndPane = false; // the fake hands out what is asked; the spares come from the owner
        sim.inv.add(MAP, 66); // two slots of spare maps: a whole stack goes back, the rest (one slot) stays
        Job job = sim.addJob("j0503", "Spares", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        int maps = sim.bot.containers.get(Sim.MAPS).items.get(MAP);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(List.of(), withdrawals(sim, Sim.MAPS), "a map was carried all along: " + sim.bot.history);
        assertEquals(maps + 64, sim.bot.containers.get(Sim.MAPS).items.get(MAP), "the whole stack above the kept one");
        assertEquals(1, sim.inv.count(MAP), "66 less the filled one, less the stack back");
        assertEquals(1, sim.inv.count(PANE), "the next tile's pane, taken at STORE");
    }

    @Test
    void aCarriedMapAndPaneDoNotSendTheBottlesWithinTheReserveBack() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        sim.config.build.reservedSlots = 3; // pickaxe, carrots and the bottles fill it; the map and pane are on top
        sim.inv.add(MAP, 1);
        sim.inv.add(PANE, 1);
        sim.inv.add(XP, 16);
        Job job = sim.addJob("j0504", "Reserve", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(30, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        assertEquals(16, sim.inv.count(XP), "no trip to the XP chest: the map and pane are the next tile's kit");
        assertEquals(0, sim.bot.count("returnWorkingItems"), sim.bot.history.toString());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(List.of(Phase.STORE), withdrawals(sim, Sim.MAPS), "only the next tile's: " + sim.bot.history);
    }

    @Test
    void anEmptyMapChestAtStoreLeavesTheMapToTheLastLoadAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        sim.bot.containers.get(Sim.MAPS).items.put(MAP, 1); // the first tile takes the last one
        Job job = sim.addJob("j0505", "Empty", 2, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + tail(log));
        assertEquals(0, sim.inv.count(MAP), "the map chest was empty at STORE");
        assertEquals(1, sim.inv.count(PANE), "the pane came all the same");
        sim.bot.containers.get(Sim.MAPS).items.put(MAP, 64); // restocked by the owner before the next tile ends
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(List.of(Phase.RESTOCK, Phase.STORE, Phase.RESTOCK, Phase.STORE), withdrawals(sim, Sim.MAPS),
            "STORE tried once, the next tile's last load fetched it: " + sim.bot.history);
        assertEquals(List.of(Phase.RESTOCK, Phase.STORE, Phase.STORE), withdrawals(sim, Sim.PANES),
            sim.bot.history.toString());
        assertEquals(2, sim.output().maps.size());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }
}
