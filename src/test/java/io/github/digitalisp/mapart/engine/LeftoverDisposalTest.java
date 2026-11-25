package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * RESTOCK gets rid of exactly the leftovers it announces (live gate C: "dropping {orange_terracotta=60}", then
 * "Dropped {black_carpet=46, orange_terracotta=60, red_carpet=2, white_carpet=54}"): a surplus of less than a stack of
 * a material the tile still needs stays in the inventory, whatever the deposit or drop task could plan on its own.
 */
class LeftoverDisposalTest {
    private static final int LONG = 200_000;
    private static final int CELLS = 2 * 128;

    /** A 2-row white job; the bot holds red carpet it does not need and 30 white carpet more than the tile needs. */
    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.inv.add(RED, 100);
        sim.inv.add(WHITE, CELLS + 30);
        return sim;
    }

    /** The "{...}" part of the first log line starting with {@code prefix}, in order of appearance. */
    private static List<String> maps(RecordingLogger log, String prefix) {
        List<String> out = new ArrayList<>();
        for (String l : log.at(Level.INFO)) {
            if (!l.startsWith(prefix)) continue;
            out.add(l.substring(l.indexOf('{'), l.indexOf('}') + 1));
        }
        return out;
    }

    @Test
    void theDropPointGetsExactlyTheAnnouncedLeftovers() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        Job job = sim.addJob("j0301", "Exact", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.droppedItems.isEmpty(), LONG), sim.state());

        assertEquals(Map.of(RED, 100), sim.bot.droppedItems, "only the announced red carpet");
        assertEquals(CELLS + 30, sim.inv.count(WHITE), "the white surplus (less than a stack) stays");
        List<String> announced = maps(log, "dropping ");
        List<String> dropped = maps(log, "dropped ");
        assertEquals(List.of("{" + RED + "=100}"), announced);
        assertEquals(announced, dropped, "every drop drops what its announcement lists");

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(maps(log, "dropping "), maps(log, "dropped "));
    }

    @Test
    void aFailedDropIsNeverCarriedToAnotherPlatformsDropPoint() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg own = new MapArtConfig.DropPointCfg();
        own.standAt = new Pos(-65, 64, -66);
        own.target = new Pos(-65, 60, -62);
        MapArtConfig.DropPointCfg far = new MapArtConfig.DropPointCfg(); // by the soul platform (centre x 256)
        far.standAt = new Pos(250, 64, -66);
        far.target = new Pos(250, 60, -62);
        sim.config.site.storage.returnDrops.add(own);
        sim.config.site.storage.returnDrops.add(far);
        sim.bot.failNext.put("drop", 1);
        Job job = sim.addJob("j0303", "Own drop", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.dropStands.contains(own.standAt), sim.bot.dropStands.toString());
        assertFalse(sim.bot.dropStands.contains(far.standAt), "never the soul platform's drop: " + sim.bot.dropStands);
    }

    @Test
    void theReturnChestGetsExactlyTheAnnouncedLeftovers() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0302", "Exact", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.containers.get(Sim.RETURN).items.isEmpty(), LONG), sim.state());

        assertEquals(Map.of(RED, 100), sim.bot.containers.get(Sim.RETURN).items, "only the announced red carpet");
        assertEquals(CELLS + 30, sim.inv.count(WHITE), "the white surplus (less than a stack) stays");
        assertEquals(List.of("{" + RED + "=100}"), maps(log, "depositing "));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.bot.droppedItems.containsKey(WHITE));
    }

    /** The platform's own return chest wins over the site's return chest and drop point (PlatformCfg.returnChests). */
    @Test
    void thePlatformsOwnReturnChestGetsTheLeftovers() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Pos own = new Pos(-82, 61, 105);
        sim.bot.addContainer(own, Map.of());
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        for (MapArtConfig.PlatformCfg p : sim.config.site.platforms) if ("moss".equals(p.id)) p.returnChests.add(own);
        Job job = sim.addJob("j0304", "Own chest", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(Map.of(RED, 100), sim.bot.containers.get(own).items, "the red carpet is in the platform's chest");
        assertTrue(sim.bot.containers.get(Sim.RETURN).items.isEmpty(), "nothing in the site's return chest");
        assertTrue(sim.bot.droppedItems.isEmpty(), "nothing at the site's drop point: " + sim.bot.droppedItems);
    }

    private static final String ROTTEN = "minecraft:rotten_flesh";
    private static final String LEAD = "minecraft:lead";
    private static final String EMERALD = "minecraft:emerald";

    private static final Pos JUNK_STAND = new Pos(-60, 64, -66);
    private static final Pos JUNK_AT = new Pos(-56, 64, -66);

    /** Mob drops the bot picked up, and things it must keep, on top of {@link #sim}'s red carpet leftovers. */
    private static Sim junkSim(RecordingLogger log, boolean dropJunk, boolean spot) {
        Sim sim = sim(log);
        sim.config.build.dropJunk = dropJunk;
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        if (spot) {
            sim.moss.junkDropStand = JUNK_STAND;
            sim.moss.junkDropAt = JUNK_AT;
        }
        sim.inv.add(ROTTEN, 40);
        sim.inv.add(LEAD, 3);
        sim.inv.add(EMERALD, 5);
        sim.inv.add("minecraft:bone", 12);
        return sim;
    }

    @Test
    void junkNeverGoesIntoTheDropPointAndStaysWithoutAJunkSpot() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = junkSim(log, true, false);
        Job job = sim.addJob("j0310", "Junk", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.droppedItems.isEmpty(), LONG), sim.state());
        assertEquals(Map.of(RED, 100), sim.bot.droppedItems, "only the leftovers go into the drop point");
        assertEquals(40, sim.inv.count(ROTTEN));
        assertEquals(3, sim.inv.count(LEAD));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(Map.of(RED, 100), sim.bot.droppedItems);
    }

    @Test
    void withAJunkSpotTheJunkIsThrownThereAndValuablesStay() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = junkSim(log, true, true);
        Job job = sim.addJob("j0313", "Spot", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.droppedItems.containsKey(ROTTEN), LONG), sim.state() + log.lines);
        assertEquals(Map.of(RED, 100, ROTTEN, 40, LEAD, 3, "minecraft:bone", 12), sim.bot.droppedItems);
        assertEquals(List.of("{" + RED + "=100}"), maps(log, "dropping "), "the drop point got only the leftovers");
        assertTrue(sim.bot.dropStands.contains(JUNK_STAND), sim.bot.dropStands.toString());
        assertEquals(5, sim.inv.count(EMERALD), "emeralds are never dropped");
        assertTrue(sim.inv.count("minecraft:golden_carrot") > 0, "food stays");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void aJunkSpotFarFromEverythingIsSkipped() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = junkSim(log, true, true);
        sim.moss.junkDropStand = new Pos(400, 64, 400);
        Job job = sim.addJob("j0314", "Far", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.droppedItems.isEmpty(), LONG), sim.state());
        assertEquals(Map.of(RED, 100), sim.bot.droppedItems);
        assertEquals(40, sim.inv.count(ROTTEN), "no long detour for junk");
    }

    @Test
    void withTheSwitchOffTheJunkStaysAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = junkSim(log, false, true);
        Job job = sim.addJob("j0311", "NoJunk", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.droppedItems.isEmpty(), LONG), sim.state());
        assertEquals(Map.of(RED, 100), sim.bot.droppedItems, "only the announced red carpet");
        assertEquals(40, sim.inv.count(ROTTEN));
        assertEquals(3, sim.inv.count(LEAD));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void junkIsNeverPutIntoAReturnChestWhichFeedsTheSorter() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log); // a return chest, no drop point
        sim.inv.add(ROTTEN, 40);
        Job job = sim.addJob("j0312", "Chest", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.bot.containers.get(Sim.RETURN).items.isEmpty(), LONG), sim.state());
        assertEquals(Map.of(RED, 100), sim.bot.containers.get(Sim.RETURN).items);
        assertEquals(40, sim.inv.count(ROTTEN));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }
}
