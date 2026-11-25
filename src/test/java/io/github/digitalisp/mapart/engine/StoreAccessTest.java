package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.Aim;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORE into an output chest that stands in a column of signed chests (the gate A layout, synthetic coordinates):
 * the production {@code Approach} runs on a simulated block world where wall signs hide faces, and the engine only
 * says "full" when the chest is full.
 */
class StoreAccessTest {
    private static final int LONG = 60_000;
    private static final Pos OUT = new Pos(-60, 62, 120);

    /** The Sim site with its output chest replaced by one in a signed column; the bot arrives on the signed side. */
    private static Sim signedColumnSite() {
        Sim sim = new Sim();
        sim.config.site.storage.outputChests.clear();
        sim.config.site.storage.outputChests.add(OUT);
        sim.bot.addContainer(OUT, Map.of());
        ContainerSim cs = sim.bot.simulateContainerAccess(21);
        sim.bot.containerStart = ContainerSites.outputColumn(cs, OUT);
        return sim;
    }

    private static Job smallJob(Sim sim) {
        TileArt art = Sim.stripes(2, 0);
        return sim.addJob("j0001", "Column", 1, 1, List.of("moss"), (x, z) -> art);
    }

    @Test
    void storeSucceedsAfterSteppingToTheVisibleSide() {
        Sim sim = signedColumnSite();
        Job job = smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(TileStatus.STORED, job.tiles.getFirst().status);
        assertEquals(1, sim.bot.containers.get(OUT).maps.size(), "the map is in the output chest");
        assertEquals(1, sim.bot.approaches);
        assertTrue(sim.bot.approachFailures.isEmpty(), sim.bot.approachFailures.toString());
        ContainerSim cs = sim.bot.containerSim;
        assertEquals(OUT, cs.opened);
        assertEquals(Aim.SOUTH, cs.lastClick.face(), "the face whose center is in sight");
        assertTrue(cs.clickEye[2] > OUT.z() + 1, "clicked from the south side");
        assertEquals(1, cs.baritoneWalks, "one walk to the stand spot");
        assertEquals(0, cs.rotateAndClick);
        assertEquals(0, cs.clickWhileMoving);
        assertEquals(cs.clickTick - 1, cs.aimedTick, "looked on the tick before the click");
        assertEquals(new Pos(OUT.x() + 1, OUT.y() - 1, OUT.z() + 1), sim.bot.standSpots.get(OUT), "spot remembered");
        assertTrue(sim.notifier.has("image", "Map done") || sim.notifier.has("info", "Map done"), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void unreachableOutputChestIsReportedWithItsReasonNotAsFull() {
        Sim sim = signedColumnSite();
        // glass in front of the south face as well (too high to stand on): no face centre in sight from anywhere
        sim.bot.containerSim.fill(OUT.x() - 1, OUT.y() - 1, OUT.z() + 1, OUT.x() + 1, OUT.y() + 3, OUT.z() + 1,
            ContainerSim.GLASS);
        Job job = smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("could not store the map"), note);
        assertTrue(note.contains("cannot reach " + OUT), note);
        assertTrue(note.contains("no stand spot"), note);
        assertTrue(note.contains("oak_wall_sign") || note.contains("glass") || note.contains("chest"), note);
        assertFalse(note.contains("full"), note);
        assertFalse(job.tiles.getFirst().status == TileStatus.STORED);
        assertTrue(sim.bot.containers.get(OUT).maps.isEmpty());
    }

    @Test
    void fullOutputChestIsReportedFull() {
        Sim sim = new Sim();
        sim.output().slots = 0;
        smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("the output chests are full"), sim.engine.status().note());
    }

    /**
     * A local run (10x under load): the deposit's menu closed before the server's answer, so the read-back still
     * showed the map in the inventory although it was in the chest: BLOCKED "took no map". Now STORE looks again a
     * moment later and counts the map stored once it has left the inventory, without a second deposit.
     */
    @Test
    void aDepositWhoseReadBackCameBeforeTheAnswerCountsOnceTheMapHasLeft() {
        Sim sim = new Sim();
        sim.bot.staleMapReadBacks = 1;
        Job job = smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(TileStatus.STORED, job.tiles.getFirst().status);
        assertEquals(1, sim.output().maps.size(), "the map is in the output chest, once");
        assertEquals(1, sim.bot.history.stream().filter(h -> h.startsWith("depositMap")).count(), "one deposit");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertTrue(sim.notifier.has("image", "Map done") || sim.notifier.has("info", "Map done"), sim.notifier.toString());
    }

    /** A map the chest really did not take: the same chest once more, then BLOCKED with the reason, as before. */
    @Test
    void aMapThatStaysInTheInventoryIsTriedOnceMoreThenBlocked() {
        Sim sim = new Sim();
        sim.bot.mapsKept = 99;
        Job job = smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("took no map") && note.contains("still in the inventory"), note);
        assertEquals(2, sim.bot.history.stream().filter(h -> h.startsWith("depositMap")).count(), "the same chest twice");
        assertFalse(job.tiles.getFirst().status == TileStatus.STORED);
        assertTrue(sim.output().maps.isEmpty());
    }

    @Test
    void anUnreachableAndAFullOutputChestAreBothNamed() {
        Sim sim = signedColumnSite();
        sim.bot.containerSim.fill(OUT.x() - 1, OUT.y() - 1, OUT.z() + 1, OUT.x() + 1, OUT.y() + 3, OUT.z() + 1,
            ContainerSim.GLASS);
        sim.config.site.storage.outputChests.add(Sim.OUTPUT);
        sim.output().slots = 0;
        smallJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("could not store the map"), note);
        assertTrue(note.contains("output chest " + OUT + " failed:") && note.contains("cannot reach"), note);
        assertTrue(note.contains("output chest " + Sim.OUTPUT + " full"), note);
    }
}
