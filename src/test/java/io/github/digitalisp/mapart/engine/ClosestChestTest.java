package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Restock takes each item from the closest chest that holds it, not the fullest: a platform lives on its own sorter and
 * uses a far one (such as another platform's sorter) only when its own runs dry.
 */
class ClosestChestTest {
    private static final int LONG = 60_000;
    private static final Pos FAR = new Pos(-96, 61, 190);

    private static Sim withFarSorter(Map<String, Integer> far) {
        Sim sim = new Sim();
        MapArtConfig.RegionCfg region = new MapArtConfig.RegionCfg();
        region.min = new Pos(-100, 60, 185);
        region.max = new Pos(-80, 62, 195);
        sim.config.site.storage.materialRegions.add(region);
        sim.bot.addContainer(FAR, far);
        return sim;
    }

    @Test
    void theClosestChestIsUsedEvenWhenAFarOneIsFuller() {
        Sim sim = withFarSorter(Map.of(WHITE, 3456));
        Job job = sim.addJob("j0001", "Near", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.count("withdraw " + FAR), sim.bot.history.toString());
    }

    @Test
    void aFarChestIsUsedWhenTheNearSorterRunsDry() {
        Sim sim = withFarSorter(Map.of(RED, 1728));
        sim.bot.addContainer(new Pos(-96, 61, 100), Map.of()); // the near RED chest is empty
        Job job = sim.addJob("j0001", "Far", 1, 1, List.of("moss"), (x, z) -> Sim.single(RED, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.count("withdraw " + FAR) >= 1, sim.bot.history.toString());
    }
}
