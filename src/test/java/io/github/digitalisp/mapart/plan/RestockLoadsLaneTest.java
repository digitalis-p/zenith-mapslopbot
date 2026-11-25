package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.lane;

/**
 * {@code RestockLoads.choose} with {@code laneCells} ({@code build.crowdLaneCells}): the crowded-load return within the
 * lane the load ends in, the carried items used furthest first (Belady).
 */
class RestockLoadsLaneTest {
    /** Items the cut lanes of {@code p} use. */
    private static Set<String> uses(List<Lane> lanes, RestockPlanner.Plan p) {
        Set<String> s = new HashSet<>();
        for (Lane l : RestockLoads.cutLanes(lanes, p)) for (Placement x : l.placements()) s.add(x.itemId());
        return s;
    }

    private static String[] row(String id) {
        return new String[] {id};
    }

    @Test
    void furthestFirstPutsUnusedItemsFirstThenTheLatestFirstUse() {
        List<Lane> lanes = List.of(lane(0, 1, true, row("a"), row("a"), row("b"), row("c"), row("c"), row("c"),
            row("a"), row("d")));
        assertEquals(List.of("z", "d", "c", "b"), RestockLoads.furthestFirst(lanes, List.of("b", "c", "d", "z")));
    }

    /**
     * The live band (2 lanes left, 40 items in runs of 1-6 rows, 24 partial stacks carried, 1 slot free): the split
     * DP's freed load with every unused stack gone covered fewer cells, and the list kept only the items it did not
     * reach; returning the furthest-used ones covers more cells with fewer returned, and the freed load reaches none.
     */
    @Test
    void theLoadCoversMoreCellsWithFewerItemsReturned() {
        List<Lane> lanes = CrowdedBandFixtures.band(1, 6, 128, 128);
        RestockPlanner.Request req = CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 8, 24));
        RestockPlanner dp = new SplitRestockPlanner(false);
        RestockLoads.Choice old = RestockLoads.choose(dp, req, id -> true, true, false);
        RestockLoads.Choice lane = RestockLoads.choose(dp, req, id -> true, true, true);
        System.out.printf("lane return: kept %d cells; old %d cells, %d items back; Belady %d cells, %d items back%n",
            old.keptCells(), old.freedCells(), old.unused().size(), lane.freedCells(), lane.unused().size());
        assertTrue(old.keptCells() < 64, "the load as carried: a few rows (" + old.keptCells() + ")");
        assertTrue(lane.clogged());
        assertTrue(lane.freedCells() > old.freedCells() + 200, lane.freedCells() + " cells, " + old.freedCells()
            + " before");
        assertTrue(lane.unused().size() < old.unused().size(), lane.unused() + " back, " + old.unused() + " before");
        assertTrue(Collections.disjoint(uses(lanes, lane.freed()), lane.unused().keySet()),
            "an item returned for this load is not withdrawn again for it");
        // what goes back is what the band uses last
        List<String> order = RestockLoads.furthestFirst(lanes, req.held().keySet());
        assertEquals(Set.copyOf(order.subList(0, lane.unused().size())), lane.unused().keySet());
    }

    @Test
    void anItemThatMayNotGoBackStays() {
        List<Lane> lanes = CrowdedBandFixtures.band(1, 6, 128, 128);
        RestockPlanner.Request req = CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 8, 24));
        List<String> order = RestockLoads.furthestFirst(lanes, req.held().keySet());
        // the furthest-used two: only in far storage, or short of what the tile still needs
        Set<String> stay = Set.of(order.get(0), order.get(1));
        RestockLoads.Choice lane = RestockLoads.choose(new SplitRestockPlanner(false), req, id -> !stay.contains(id),
            true, true);
        assertTrue(lane.clogged());
        assertTrue(Collections.disjoint(lane.unused().keySet(), stay), lane.unused() + " back");
        assertTrue(Collections.disjoint(uses(lanes, lane.freed()), lane.unused().keySet()));
    }

    @Test
    void aLoadThatIsNotCrowdedIsUnchanged() {
        // 12 stacks carried, 13 slots free: the DP's load ends at the lane end with room to spare
        List<Lane> lanes = CrowdedBandFixtures.band(1, 6, 92, 128);
        RestockPlanner.Request req = CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 2, 12));
        RestockPlanner dp = new SplitRestockPlanner(false);
        RestockLoads.Choice old = RestockLoads.choose(dp, req, id -> true, true, false);
        RestockLoads.Choice lane = RestockLoads.choose(dp, req, id -> true, true, true);
        assertEquals(old, lane);
        // nothing carried: nothing to return
        RestockPlanner.Request empty = CrowdedBandFixtures.request(lanes, Map.of());
        RestockLoads.Choice none = RestockLoads.choose(dp, empty, id -> true, true, true);
        assertNull(none.freed());
        assertFalse(none.clogged());
        assertEquals(RestockLoads.choose(dp, empty, id -> true, true, false), none);
    }

    @Test
    void withoutKeepReusedTheListIsAsBefore() {
        List<Lane> lanes = CrowdedBandFixtures.band(1, 6, 128, 128);
        RestockPlanner.Request req = CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 8, 24));
        RestockPlanner g = new GreedyRestockPlanner();
        assertEquals(RestockLoads.choose(g, req, id -> true, false), RestockLoads.choose(g, req, id -> true, false, true));
    }
}
