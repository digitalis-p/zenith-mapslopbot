package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The split DP's carried-stacks guard ({@code build.restockCarriedGuard}, {@link SplitDp#launders}). Live 2026-10-31
 * (map "haruhi", lanes 6-7): "load covers 0 + 9 cells of the next, withdraw {birch_pressure_plate=64} (planned: 3 loads
 * left)" six times in a row. The later-round model counts only a round's own cells, so with partial stacks carried for
 * further on a first load of one run, then "the rest next load", looked cheapest; the next replan had the same stacks.
 */
class SplitRestockPlannerCarriedTest {
    /** The rest of a half-built lane (92 rows) and the next (128), 22 stacks carried, 3 slots free. */
    private static RestockPlanner.Request crowded() {
        List<Lane> lanes = CrowdedBandFixtures.band(4, 6, 92, 128);
        return CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 2, 22));
    }

    @Test
    void aShortFirstLoadOnARoundTwoWithoutRoomGivesWayToTheMaxFill() {
        RestockPlanner.Request req = crowded();
        SplitRestockPlanner.Result model = new SplitRestockPlanner(false).solve(req);
        int fill = RestockLoads.cellsLoaded(req.remaining(), new GreedyRestockPlanner().plan(req));
        int dp = model.rounds().getFirst().cells();
        assertTrue(SplitDp.launders(req, model.rounds()), "round 2 does not fit with the carried stacks");
        assertTrue(dp < fill / 2, "the DP's own first load: " + dp + " cells of " + fill);
        for (boolean lanesFirst : new boolean[] {false, true}) {
            RestockPlanner.Plan guarded = new SplitRestockPlanner(lanesFirst, () -> true).plan(req);
            int cells = RestockLoads.cellsLoaded(req.remaining(), guarded);
            System.out.printf("carried guard (lanesFirst %b): first load %d -> %d cells (max fill %d)%n", lanesFirst, dp,
                cells, fill);
            assertEquals(fill, cells, "the longest first load that fits");
            assertTrue(guarded.stopAtCut());
            assertTrue(guarded.rounds() >= 2 && guarded.seconds() > 0, "still the DP's plan, with its estimate");
        }
    }

    @Test
    void offOrRoomEnoughThePlanIsTheDpsOwn() {
        RestockPlanner.Request req = crowded();
        assertEquals(new SplitRestockPlanner(false).plan(req), new SplitRestockPlanner(false, () -> false).plan(req));
        // 12 stacks carried, 13 slots free: the DP's first load ends at the lane end and round 2 fits
        List<Lane> lanes = CrowdedBandFixtures.band(1, 6, 92, 128);
        RestockPlanner.Request roomy = CrowdedBandFixtures.request(lanes, CrowdedBandFixtures.carried(lanes, 2, 12));
        SplitRestockPlanner.Result model = new SplitRestockPlanner(false).solve(roomy);
        assertFalse(SplitDp.launders(roomy, model.rounds()));
        assertEquals(model.plan(), new SplitRestockPlanner(false, () -> true).plan(roomy));
        // nothing carried: the later-round model is exact about the stacks
        RestockPlanner.Request none = CrowdedBandFixtures.request(lanes, Map.of());
        assertFalse(SplitDp.launders(none, new SplitRestockPlanner(false).solve(none).rounds()));
        assertEquals(new SplitRestockPlanner(false).plan(none), new SplitRestockPlanner(false, () -> true).plan(none));
    }

    /**
     * Lanes of {@code rows} 9-wide rows of {@code items} random items ("c0".."), each after a row of 2 moss (a merged
     * noobline row): the moss carried for the next lane's start.
     */
    private static List<Lane> nooblineBand(long seed, int lanes, int rows, int items) {
        Random r = new Random(seed);
        String[][][] ls = new String[lanes][][];
        for (int j = 0; j < lanes; j++) {
            String[][] rs = new String[rows + 1][];
            rs[0] = new String[] {"moss", "moss"};
            for (int q = 1; q <= rows; q++) {
                String[] row = new String[9];
                for (int c = 0; c < 9; c++) row[c] = "c" + r.nextInt(items);
                rs[q] = row;
            }
            ls[j] = rs;
        }
        return SplitRestockPlannerFixtures.serpentine(ls);
    }

    /** 45 moss carried in {@code mossSlots} slots, 24 empty ones, top-up, every depot at one spot. */
    private static RestockPlanner.Request mossCarried(List<Lane> lanes, int mossSlots) {
        RestockPlanner.Site site = new RestockPlanner.Site(new Pos(0, 0, -20), id -> new Pos(-10, 0, -30),
            RestockPlanner.Costs.DEFAULT);
        return new RestockPlanner.Request(lanes, Map.of("moss", 45), Map.of("moss", mossSlots), 24, id -> 64, 0, true,
            site);
    }

    /**
     * Local 10x colour suppression run 2026-11-14 (the merged noobline row, 45 moss carried in 2 slots): a load of 7
     * cells. The capacity counts the moss's 2 slots, the guard counted {@code ceil(45 / 64)} = 1 for it, so the DP's
     * round 2 "fit" with no slot to spare and its short round 1 stood; carried, round 2 needs one slot more.
     */
    @Test
    void aSplitPartialStackCountsEverySlotItHolds() {
        List<Lane> lanes = nooblineBand(9, 3, 48, 12);
        RestockPlanner.Request split = mossCarried(lanes, 2);
        SplitRestockPlanner.Result model = new SplitRestockPlanner(false).solve(split);
        int dp = model.rounds().getFirst().cells();
        assertTrue(dp < 20, "the DP's own first load: the moss row and a row: " + dp);
        assertTrue(SplitDp.launders(split, model.rounds()), "round 2 does not fit with the moss in its 2 slots");
        int fill = RestockLoads.cellsLoaded(lanes, new GreedyRestockPlanner().plan(split));
        // SPLIT, as AUTO plans 9-wide lanes
        RestockPlanner.Plan guarded = new SplitRestockPlanner(false, () -> true).plan(split);
        int cells = RestockLoads.cellsLoaded(lanes, guarded);
        System.out.printf("split moss: first load %d -> %d cells (max fill %d)%n", dp, cells, fill);
        assertTrue(cells > 1000, "the first load as carried: " + cells);
        assertEquals(fill, cells, "the longest first load that fits");
        assertTrue(guarded.stopAtCut());
    }

    @Test
    void carriedSlotsAreWholeStacksPlusTheSplitOnesRoundOneDidNotFill() {
        // 45 moss in 2 slots, none used by round 1 (or 2 of them): both slots go on carrying it
        assertEquals(2, SplitDp.carriedSlots(45, 2, 0, 45, 64));
        assertEquals(2, SplitDp.carriedSlots(45, 2, 0, 43, 64));
        // round 1 tops both up (100 cells: 128 in 2 whole stacks): what is left is one partial stack
        assertEquals(1, SplitDp.carriedSlots(45, 2, 83, 28, 64));
        // 3 slots, round 1 takes 2 stacks for 150 cells (192 in 3): no slot more than whole stacks
        assertEquals(1, SplitDp.carriedSlots(45, 3, 147, 42, 64));
        // a slot holds one item at least: 1 left of 2 split ones is one slot
        assertEquals(1, SplitDp.carriedSlots(2, 2, 0, 1, 64));
        // 130 in 4 slots (3 whole stacks' worth + 1 split), none used
        assertEquals(4, SplitDp.carriedSlots(130, 4, 0, 130, 64));
        // nothing split: ceil(c / stack), the count before the fix
        for (int h = 0; h <= 200; h += 7) {
            for (int w = 0; w <= 192; w += 64) {
                int u = (h + 63) / 64;
                for (int c = 1; c <= h + w; c += 5) {
                    assertEquals((c + 63) / 64, SplitDp.carriedSlots(h, u, w, c, 64), h + " " + w + " " + c);
                }
            }
        }
    }

    @Test
    void anUnsplitInventoryKeepsThePlanItHadBefore() {
        // heldSlots absent and heldSlots of whole stacks plan alike, guard on or off as before
        List<Lane> lanes = CrowdedBandFixtures.band(4, 6, 92, 128);
        Map<String, Integer> held = CrowdedBandFixtures.carried(lanes, 2, 22);
        Map<String, Integer> whole = new TreeMap<>();
        held.forEach((id, n) -> whole.put(id, (n + 63) / 64));
        RestockPlanner.Request none = CrowdedBandFixtures.request(lanes, held);
        RestockPlanner.Request compact = new RestockPlanner.Request(lanes, held, whole, none.freeSlots(), id -> 64, 0,
            true, none.site());
        for (boolean lanesFirst : new boolean[] {false, true}) {
            assertEquals(new SplitRestockPlanner(lanesFirst, () -> true).plan(none),
                new SplitRestockPlanner(lanesFirst, () -> true).plan(compact));
        }
        RestockPlanner.Request moss = mossCarried(nooblineBand(9, 3, 48, 12), 1);
        SplitRestockPlanner.Result model = new SplitRestockPlanner(false).solve(moss);
        assertFalse(SplitDp.launders(moss, model.rounds()), "45 moss in one slot: round 2 fits");
        assertEquals(model.plan(), new SplitRestockPlanner(false, () -> true).plan(moss));
    }

    @Test
    void theConfiguredPlannerReadsTheSwitchEachTime() {
        RestockPlanner.Request req = crowded();
        boolean[] on = {false};
        ConfiguredRestockPlanner p = new ConfiguredRestockPlanner(() -> "SPLIT", () -> on[0]);
        RestockPlanner.Plan off = p.plan(req);
        on[0] = true;
        RestockPlanner.Plan guarded = p.plan(req);
        assertTrue(RestockLoads.cellsLoaded(req.remaining(), guarded) > RestockLoads.cellsLoaded(req.remaining(), off));
        assertEquals(guarded, new ConfiguredRestockPlanner(() -> "SPLIT").plan(req), "on by default");
    }
}
