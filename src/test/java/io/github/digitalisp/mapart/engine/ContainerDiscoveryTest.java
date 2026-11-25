package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerDiscoveryTest {
    private static BlockSpec chest(String facing, String type) {
        return BlockSpec.of("minecraft:chest", Map.of("facing", facing, "type", type, "waterlogged", "false"));
    }

    private static List<Pos> discover(FakeWorld w, Pos min, Pos max, Set<Pos> roles) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = min;
        r.max = max;
        List<Pos> out = new ArrayList<>();
        ContainerDiscovery d = new ContainerDiscovery(w, r, roles, res -> out.addAll(res.containers()));
        while (d.tick() == TaskStatus.RUNNING) { }
        return out;
    }

    @Test
    void doubleChestsInARowArePairedByHandedness() {
        FakeWorld w = new FakeWorld();
        // facing north: LEFT half's partner is east (+x). Two double chests side by side: A|B C|D
        w.set(new Pos(0, 0, 0), chest("north", "left"));
        w.set(new Pos(1, 0, 0), chest("north", "right"));
        w.set(new Pos(2, 0, 0), chest("north", "left"));
        w.set(new Pos(3, 0, 0), chest("north", "right"));
        w.set(new Pos(5, 0, 0), BlockSpec.of("minecraft:barrel", Map.of("facing", "up", "open", "false")));
        w.set(new Pos(6, 0, 0), chest("south", "single"));
        List<Pos> found = discover(w, new Pos(0, 0, 0), new Pos(10, 0, 0), Set.of());
        assertEquals(List.of(new Pos(0, 0, 0), new Pos(2, 0, 0), new Pos(5, 0, 0), new Pos(6, 0, 0)), found);
    }

    @Test
    void facingEastPairsAlongZ() {
        FakeWorld w = new FakeWorld();
        // facing east: LEFT partner is south (+z)
        w.set(new Pos(0, 0, 0), chest("east", "left"));
        w.set(new Pos(0, 0, 1), chest("east", "right"));
        assertEquals(List.of(new Pos(0, 0, 0)), discover(w, new Pos(0, 0, 0), new Pos(0, 0, 3), Set.of()));
    }

    @Test
    void roleChestsAreExcludedWithBothHalves() {
        FakeWorld w = new FakeWorld();
        w.set(new Pos(0, 0, 0), chest("north", "left"));
        w.set(new Pos(1, 0, 0), chest("north", "right"));
        w.set(new Pos(4, 0, 0), chest("north", "single"));
        // role configured at the RIGHT half only: the whole double chest is excluded
        List<Pos> found = discover(w, new Pos(0, 0, 0), new Pos(5, 0, 0), Set.of(new Pos(1, 0, 0)));
        assertEquals(List.of(new Pos(4, 0, 0)), found);
    }
}
