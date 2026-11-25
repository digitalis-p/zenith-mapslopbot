package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast nuke's edge guard ({@link NukeGuard}) in whole runs ({@link NukeSim}): the live 2026-10-18 east edge. The
 * art fills the tile's east part up to its edge column (x 127), so the last lane's line is the edge column itself; the
 * no-go zone over the item trench starts at x 128 (floor under it, nothing in the way: the box can walk in). The keys
 * are those of yaw 8° (the walk drifts towards the zone), and a row of blocks across the lane stops the box although
 * the walkable test passes it (decorated pots, inset 1/16).
 */
class NukeEdgeGuardTest {
    static final int Y = NukeRunTest.Y;

    static NukeRunTest.Scenario eastEdge(String name, boolean guardOff, double yaw) {
        Map<Pos, BlockType> art = new LinkedHashMap<>();
        for (int x = 100; x < 128; x++) {
            for (int z = 20; z <= 100; z++) {
                if (z == 60) continue; // the pots' row
                art.put(new Pos(x, Y, z), NukeRunTest.GRAY_TERRACOTTA);
            }
        }
        NukeRunTest.Scenario s = new NukeRunTest.Scenario(name);
        s.blocks = art;
        s.perTick = 1;
        s.startX = 127.5;
        s.startZ = 101.5;
        s.client = c -> {
            c.floorAt = p -> p.x() >= 90 && p.x() <= 131 && p.z() >= 10 && p.z() <= 110;
            c.hazardAt = p -> p.x() >= 128;
            c.guardOff = guardOff;
            c.keyYaw = yaw;
            c.hiddenObstacle = p -> p.z() == 60 && p.x() >= 118 && p.x() <= 127;
        };
        return s;
    }

    /** The whole east part is cleared, the box never in the zone. */
    @Test
    void sweepsTheEdgeLaneWithoutEnteringTheZone() {
        for (double yaw : new double[]{8, -8, 20, 0}) {
            NukeRunTest.Result r = eastEdge("edge guard, yaw " + yaw, false, yaw).run();
            System.out.println(r.line() + "; " + r.run().summaryLine("done"));
            assertEquals(TaskStatus.SUCCESS, r.status(), r.name() + ": " + r.sim().log);
            assertEquals(r.targets().size(), r.run().done(), r.name());
            assertEquals(0, r.sim().hazardTicks, r.name() + ": in the zone at " + r.sim().firstHazard);
            r.assertRules(1);
        }
    }

    /** The same with the guard off ({@code build.nukeEdgeGuard} false): the box goes into the zone, as live. */
    @Test
    void withoutTheGuardTheBoxGoesIn() {
        NukeRunTest.Result r = eastEdge("edge guard off, yaw 8", true, 8).run();
        System.out.println(r.line() + "; " + r.sim().hazardTicks + " ticks in the zone, first at " + r.sim().firstHazard);
        assertTrue(r.sim().hazardTicks > 0, "the scenario reproduces the live walk into the zone");
    }
}
