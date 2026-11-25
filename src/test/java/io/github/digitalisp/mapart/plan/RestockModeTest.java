package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code build.restockPlanner}: parsing, AUTO by lane width, and the planner each value picks. */
class RestockModeTest {
    /** A lane {@code width} columns wide and 3 rows long. */
    private static Lane lane(int width) {
        List<Placement> ps = new ArrayList<>();
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < width; x++) ps.add(new Placement(new Pos(x, 64, z), BlockSpec.of("minecraft:white_carpet"),
                "minecraft:white_carpet"));
        return new Lane(0, new Pos(width / 2, 64, -1), new Pos(width / 2, 64, 3), ps);
    }

    @Test
    void valuesAreReadLeniently() {
        assertEquals(RestockMode.SPLIT, RestockMode.of(" split "));
        assertEquals(RestockMode.LANES, RestockMode.of("Lanes"));
        assertEquals(RestockMode.GREEDY, RestockMode.of("GREEDY"));
        assertEquals(RestockMode.AUTO, RestockMode.of("AUTO"));
        assertEquals(RestockMode.AUTO, RestockMode.of("whatever"));
        assertEquals(RestockMode.AUTO, RestockMode.of(null));
    }

    @Test
    void autoUsesTheTwoLaneRuleUpToFiveWideLanes() {
        assertEquals(RestockMode.LANES, RestockMode.AUTO.resolve(List.of(lane(5))));
        assertEquals(RestockMode.LANES, RestockMode.AUTO.resolve(List.of(lane(3), lane(5))));
        assertEquals(RestockMode.SPLIT, RestockMode.AUTO.resolve(List.of(lane(5), lane(9))));
        assertEquals(RestockMode.SPLIT, RestockMode.SPLIT.resolve(List.of(lane(5))));
        assertEquals(RestockMode.GREEDY, RestockMode.GREEDY.resolve(List.of(lane(9))));
    }

    @Test
    void theConfiguredPlannerFollowsTheConfigOnEveryLoad() {
        AtomicReference<String> mode = new AtomicReference<>("GREEDY");
        ConfiguredRestockPlanner p = new ConfiguredRestockPlanner(mode::get);
        List<Lane> wide = List.of(lane(9));
        List<Lane> narrow = List.of(lane(5));
        assertInstanceOf(GreedyRestockPlanner.class, p.forLanes(wide));
        assertFalse(p.expensive());
        mode.set("AUTO");
        assertTrue(p.expensive());
        assertFalse(((SplitRestockPlanner) p.forLanes(wide)).lanesFirst(), "9-wide: SPLIT");
        assertTrue(((SplitRestockPlanner) p.forLanes(narrow)).lanesFirst(), "5-wide: LANES");
        mode.set("LANES");
        assertTrue(((SplitRestockPlanner) p.forLanes(wide)).lanesFirst());
        mode.set("SPLIT");
        assertFalse(((SplitRestockPlanner) p.forLanes(narrow)).lanesFirst());
        RestockPlanner.Plan plan = p.plan(narrow, Map.of(), 4, i -> 64);
        assertTrue(plan.stopAtCut(), "the split planner's load is cut where it ends");
        assertEquals(1, plan.lanesCovered());
    }
}
