package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FarStorage}: what is far, the far part of a load, the round-up cap, the leave-out of a few far cells, batching. */
class FarStorageTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);
    private static final ToIntFunction<String> STACK64 = i -> 64;

    private static RestockPlanner.Request req(List<Lane> lanes, Map<String, Integer> held, int free) {
        return new RestockPlanner.Request(lanes, held, Map.of(), free, STACK64, 0, false, null);
    }

    /** A lane placing the given items, one placement per entry, in the given order. */
    private static Lane lane(int index, String... items) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < items.length; i++)
            ps.add(new Placement(new Pos(index, 0, i), BlockSpec.of(items[i]), items[i]));
        return new Lane(index, new Pos(index, 0, -1), new Pos(index, 0, items.length), ps);
    }

    private static String[] times(int n, String item) {
        String[] a = new String[n];
        Arrays.fill(a, item);
        return a;
    }

    private static String[] concat(String[]... parts) {
        List<String> l = new ArrayList<>();
        for (String[] p : parts) l.addAll(List.of(p));
        return l.toArray(new String[0]);
    }

    /** {@code n} lanes of 192 near cells ("n") then {@code f} cells of "f". */
    private static List<Lane> lanes(int n, int f) {
        List<Lane> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(lane(i, concat(times(192, "n"), times(f, "f"))));
        return l;
    }

    /** "n" near, "f" in far region 0, plenty of both. */
    private static FarStorage.Stock nearAndFar() {
        return new FarStorage.Stock(Map.of("n", 100_000), Map.of("f", Map.of(0, 100_000)));
    }

    // ---------------------------------------------------------------------------------------------- what is far

    @Test
    void farIsMeasuredFromTheMapAreaNotItsCentre() {
        assertEquals(0, FarStorage.distanceToArea(GEO, 0, 0));
        assertEquals(0, FarStorage.distanceToArea(GEO, 63, -64));
        assertEquals(7, FarStorage.distanceToArea(GEO, -10, -71), 1e-9); // the own sorter, north of the area
        assertEquals(5, FarStorage.distanceToArea(GEO, -67, -68), 1e-9); // 3 west, 4 north of the NW corner
        // the other platform's sorter, west: 129 blocks from this area's west edge
        assertTrue(FarStorage.far(GEO, 64, new Pos(-193, 174, -70)));
        assertFalse(FarStorage.far(GEO, 64, new Pos(-10, 174, -71)));
        assertFalse(FarStorage.far(GEO, 0, new Pos(-193, 174, -70)), "0 blocks: nothing is far");
        assertFalse(FarStorage.far(null, 64, new Pos(-193, 174, -70)));
    }

    @Test
    void theFarPartIsWhatHeldAndNearStockLeave() {
        List<Lane> l = List.of(lane(0, concat(times(100, "a"), times(50, "b"), times(30, "c"))));
        FarStorage.Stock s = new FarStorage.Stock(Map.of("a", 60, "b", 500),
            Map.of("a", Map.of(0, 1000), "c", Map.of(1, 10)));
        // a: 100 − 20 held − 60 near = 20 from far; b: near covers it; c: 30 needed, far holds only 10
        assertEquals(Map.of("a", 20, "c", 10), FarStorage.farPart(l, Map.of("a", 20), s));
        assertEquals(Map.of(), FarStorage.farPart(l, Map.of(), FarStorage.Stock.NONE));
        assertEquals(1010, new FarStorage.Stock(Map.of(), Map.of("a", Map.of(0, 1000, 1, 10))).far("a"));
    }

    @Test
    void theStackRoundUpNeverComesFromFar() {
        // live 07:13:25: a top-up asked 253 light blue terracotta, the near chest gave 246, 7 came from the far sorter
        assertEquals(0, FarStorage.farCap(249, 3, 246), "the 249 cells were covered: the round-up stays near");
        assertEquals(5, FarStorage.farCap(254, 3, 246));
        assertEquals(0, FarStorage.farCap(10, 20, 0));
    }

    // ---------------------------------------------------------------------------------- no trip for a few blocks

    @Test
    void aLoadWithFewFarCellsLeavesThemOut() {
        // live 07:47: the load needs 60 "f", the near chest holds 51: 9 from far; the later lanes need 180 more from
        // far, so a later load goes there anyway
        List<Lane> l = lanes(4, 60);
        List<Lane> load = l.subList(0, 1);
        FarStorage.Stock s = new FarStorage.Stock(Map.of("n", 10_000, "f", 51), Map.of("f", Map.of(0, 1000)));
        FarStorage.LeftOut out = FarStorage.fewFarCells(l, load, Map.of(), s, 64);
        assertNotNull(out);
        assertEquals(Map.of("f", 9), out.load());
        assertEquals(9, out.loadCells());
        // every "f" cell near storage cannot cover waits, in all the lanes: 240 − 51
        assertEquals(Map.of("f", 189), out.cells());
        assertEquals(189, out.count());
        assertEquals(51, FarStorage.cells(out.lanes()).get("f"));
        assertEquals(768, FarStorage.cells(out.lanes()).get("n"));
        // the first 51 stay: the near ones
        assertEquals(51, out.lanes().getFirst().placements().stream().filter(p -> p.itemId().equals("f")).count());
    }

    @Test
    void aLoadThatGoesFarAnywayTakesItsSmallItemsAlong() {
        // 9 "f" and 100 "g" from far: 109 cells are worth the trip
        List<Lane> l = List.of(lane(0, concat(times(100, "n"), times(60, "f"), times(100, "g"))), lane(1, times(100, "n")));
        FarStorage.Stock s = new FarStorage.Stock(Map.of("n", 10_000, "f", 51),
            Map.of("f", Map.of(0, 1000), "g", Map.of(0, 1000)));
        assertNull(FarStorage.fewFarCells(l, l.subList(0, 1), Map.of(), s, 64));
        // without g (carried): 9 cells, and no later load goes far: taken now
        assertNull(FarStorage.fewFarCells(l, l.subList(0, 1), Map.of("g", 100), s, 64));
    }

    @Test
    void nothingIsLeftOutWhenNothingElseIsLeftOrTheRuleIsOff() {
        FarStorage.Stock s = nearAndFar();
        List<Lane> onlyFar = List.of(lane(0, times(30, "f")));
        assertNull(FarStorage.fewFarCells(onlyFar, onlyFar, Map.of(), s, 64), "nothing else left: the trip goes");
        List<Lane> l = lanes(3, 10);
        assertNull(FarStorage.fewFarCells(l, l, Map.of(), s, 0), "farTripMinCells 0");
        assertNull(FarStorage.fewFarCells(l, l, Map.of("f", 30), s, 64), "carried: no far cells");
        assertNull(FarStorage.fewFarCells(l, l, Map.of(), FarStorage.Stock.NONE, 64), "no far stock");
        assertNull(FarStorage.fewFarCells(lanes(7, 10), lanes(7, 10), Map.of(), s, 64), "70 cells: worth a trip");
        assertNull(FarStorage.fewFarCells(l, l, Map.of(), s, 64), "30 cells, but no later far trip: taken now");
    }

    /** The two branches: a few far cells wait only for a later load that goes there anyway, else they go now. */
    @Test
    void aFewFarCellsWaitOnlyForALaterFarTrip() {
        FarStorage.Stock s = nearAndFar();
        // 10 lanes of 10 far cells: the first lane's 10 wait, 90 more come later (a later load goes there)
        List<Lane> ten = lanes(10, 10);
        FarStorage.LeftOut out = FarStorage.fewFarCells(ten, ten.subList(0, 1), Map.of(), s, 64);
        assertNotNull(out, "90 far cells after this load: they go with that trip");
        assertEquals(Map.of("f", 10), out.load());
        assertEquals(Map.of("f", 100), out.cells());
        // 7 lanes: 10 now, 60 later, fewer than 64: no later trip is sure to go, so this load takes its 10 now
        List<Lane> seven = lanes(7, 10);
        assertNull(FarStorage.fewFarCells(seven, seven.subList(0, 1), Map.of(), s, 64));
        // 8 lanes: 70 later
        List<Lane> eight = lanes(8, 10);
        assertNotNull(FarStorage.fewFarCells(eight, eight.subList(0, 1), Map.of(), s, 64));
        // the later far cells of other items count too
        List<Lane> mixed = new ArrayList<>(lanes(1, 10));
        mixed.add(lane(1, times(80, "g")));
        FarStorage.Stock fg = new FarStorage.Stock(Map.of("n", 10_000), Map.of("f", Map.of(0, 1000), "g", Map.of(0, 1000)));
        FarStorage.LeftOut withG = FarStorage.fewFarCells(mixed, mixed.subList(0, 1), Map.of(), fg, 64);
        assertNotNull(withG);
        assertEquals(Map.of("f", 10), withG.cells(), "only the load's items wait");
        assertEquals(1, FarStorage.laterFarCells(Map.of("f", 11), Map.of("f", 10)));
    }

    @Test
    void theCellsLeftOutAreEachItemsLastOnes() {
        // 6 lanes of 20 "f" + 10 "n", near storage holds 25 "f": a load of 2 lanes needs 15 from far, 80 more come
        // later; the first 25 stay (withdrawn near), the last 95 wait
        List<Lane> l = new ArrayList<>();
        for (int i = 0; i < 6; i++) l.add(lane(i, concat(times(20, "f"), times(10, "n"))));
        FarStorage.Stock s = new FarStorage.Stock(Map.of("n", 100, "f", 25), Map.of("f", Map.of(0, 1000)));
        FarStorage.LeftOut out = FarStorage.fewFarCells(l, l.subList(0, 2), Map.of(), s, 64);
        assertNotNull(out);
        assertEquals(Map.of("f", 15), out.load());
        assertEquals(Map.of("f", 95), out.cells());
        assertEquals(20, out.lanes().get(0).placements().stream().filter(p -> p.itemId().equals("f")).count());
        assertEquals(5, out.lanes().get(1).placements().stream().filter(p -> p.itemId().equals("f")).count());
        assertEquals("f", out.lanes().get(1).placements().get(4).itemId());
        assertEquals("n", out.lanes().get(1).placements().get(5).itemId());
        assertEquals(0, out.lanes().get(2).placements().stream().filter(p -> p.itemId().equals("f")).count());
    }

    // ------------------------------------------------------------------------------------------------- batching

    @Test
    void aLoadThatGoesFarTakesTheFarItemsOfTheUpcomingLanes() {
        // 8 lanes of 192 near + 16 far cells, 10 free slots: alone a load covers 3 lanes (9 + 1 slots) and every
        // third lane's load goes far again for 48 cells
        List<Lane> l = lanes(8, 16);
        GreedyRestockPlanner inner = new GreedyRestockPlanner();
        RestockPlanner.Plan alone = inner.plan(req(l, Map.of(), 10));
        assertEquals(3, alone.lanesCovered());
        assertEquals(48, alone.withdraw().get("f"));
        FarStorage.Batching b = new FarStorage.Batching(inner, nearAndFar());
        RestockPlanner.Plan p = b.plan(req(l, Map.of(), 10));
        FarStorage.Batch batch = b.batchOf(p);
        assertNotNull(batch, p.toString());
        assertEquals(Map.of("f", 128), batch.cells(), "all 8 lanes' far cells in 2 slots");
        assertEquals(Map.of("f", 128), batch.take());
        assertEquals(8, batch.lanes());
        assertEquals(java.util.Set.of(0), batch.regions());
        assertEquals(128, p.withdraw().get("f"));
        // the near part of the load gets the other 8 slots: 2 lanes and 128 cells of the third
        assertEquals(2, p.lanesCovered());
        assertEquals(128, p.partialCells());
        assertEquals(512, p.withdraw().get("n"));
    }

    @Test
    void aLoadThatStaysNearIsPlannedAsBefore() {
        // the far cells are held already: no far trip, nothing batched
        List<Lane> l = lanes(8, 16);
        GreedyRestockPlanner inner = new GreedyRestockPlanner();
        RestockPlanner.Request r = req(l, Map.of("f", 128), 8);
        FarStorage.Batching b = new FarStorage.Batching(inner, nearAndFar());
        RestockPlanner.Plan p = b.plan(r);
        assertEquals(inner.plan(r), p);
        assertNull(b.batchOf(p));
    }

    @Test
    void onlyTheFarRegionTheLoadVisitsIsBatched() {
        // f in region 0 (this load needs it), g in region 1 (later lanes only): g waits for its own trip
        List<Lane> l = new ArrayList<>();
        l.add(lane(0, concat(times(64, "n"), times(10, "f"))));
        for (int i = 1; i < 6; i++) l.add(lane(i, concat(times(64, "n"), times(10, "f"), times(10, "g"))));
        FarStorage.Stock s = new FarStorage.Stock(Map.of("n", 10_000),
            Map.of("f", Map.of(0, 1000), "g", Map.of(1, 1000)));
        FarStorage.Batching b = new FarStorage.Batching(new GreedyRestockPlanner(), s);
        RestockPlanner.Plan p = b.plan(req(l, Map.of(), 3));
        FarStorage.Batch batch = b.batchOf(p);
        assertNotNull(batch, p.toString());
        assertEquals(Map.of("f", 60), batch.cells());
        assertFalse(p.withdraw().containsKey("g"), "no g for later lanes: " + p);
        assertEquals(java.util.Set.of(0), batch.regions());
    }

    @Test
    void theBatchLeavesRoomForTheNearItemsOfTheFirstLane() {
        // the far cells of 6 lanes take 12 slots, more than the load holds: the batch covers as many leading lanes as
        // fit next to lane 0's 3 near slots
        List<Lane> l = lanes(6, 128);
        FarStorage.Batching b = new FarStorage.Batching(new GreedyRestockPlanner(), nearAndFar());
        RestockPlanner.Plan p = b.plan(req(l, Map.of(), 10));
        FarStorage.Batch batch = b.batchOf(p);
        assertNotNull(batch, p.toString());
        // 7 slots for far: 3 lanes' 384 far cells take 6, 4 lanes' 512 take 8
        assertEquals(3, batch.lanes());
        assertEquals(Map.of("f", 384), batch.cells());
        assertTrue(p.lanesCovered() >= 1, "the first lane is still covered: " + p);
        int slots = 384 / 64 + (p.withdraw().get("n") + 63) / 64;
        assertTrue(slots <= 10, "within the load's slots: " + p);
    }

    @Test
    void withTopUpTheBatchTakesWholeStacks() {
        List<Lane> l = lanes(8, 16);
        RestockPlanner.Request r = new RestockPlanner.Request(l, Map.of("f", 10), Map.of("f", 1), 9, STACK64, 0, true, null);
        FarStorage.Batching b = new FarStorage.Batching(new GreedyRestockPlanner(), nearAndFar());
        RestockPlanner.Plan p = b.plan(r);
        FarStorage.Batch batch = b.batchOf(p);
        assertNotNull(batch, p.toString());
        assertEquals(Map.of("f", 128), batch.cells());
        assertEquals(Map.of("f", 118), batch.take(), "topped up to 2 whole stacks");
        assertEquals(118, p.withdraw().get("f"));
    }

    @Test
    void noFarStockMeansTheInnerPlannerAlone() {
        List<Lane> l = lanes(4, 16);
        GreedyRestockPlanner inner = new GreedyRestockPlanner();
        FarStorage.Batching b = new FarStorage.Batching(inner, new FarStorage.Stock(Map.of("n", 10_000, "f", 10_000),
            new LinkedHashMap<>()));
        RestockPlanner.Plan p = b.plan(req(l, Map.of(), 10));
        assertEquals(inner.plan(req(l, Map.of(), 10)), p);
        assertNull(b.batchOf(p));
        assertEquals(inner.expensive(), b.expensive());
    }

    @Test
    void theSplitPlannerBatchesToo() {
        List<Lane> l = lanes(8, 16);
        FarStorage.Batching b = new FarStorage.Batching(new SplitRestockPlanner(false), nearAndFar());
        RestockPlanner.Plan p = b.plan(req(l, Map.of(), 10));
        FarStorage.Batch batch = b.batchOf(p);
        assertNotNull(batch, p.toString());
        assertEquals(Map.of("f", 128), batch.cells());
        assertTrue(b.expensive());
    }

    @Test
    void theMarginKeepsTheLoadsBeforeTheNextFarTripHealthy() {
        // 192 near (3 slots) + 64 far cells per lane, 12 slots: batching all 8 lanes fits this load (3 + 8), but at
        // lane 1 the 7 far slots left and its 3 near slots leave no room for the partial stacks of the next lanes
        List<Lane> l = lanes(8, 64);
        FarStorage.Batching tight = new FarStorage.Batching(new GreedyRestockPlanner(), nearAndFar(), 0);
        FarStorage.Batching dflt = new FarStorage.Batching(new GreedyRestockPlanner(), nearAndFar());
        FarStorage.Batch all = tight.batchOf(tight.plan(req(l, Map.of(), 12)));
        FarStorage.Batch kept = dflt.batchOf(dflt.plan(req(l, Map.of(), 12)));
        assertNotNull(all);
        assertNotNull(kept);
        assertEquals(8, all.lanes());
        assertEquals(7, kept.lanes(), "a quarter of the 12 slots (3) stays free at lane 1: 6 far + 3 near + 3");
        assertEquals(Map.of("f", 448), kept.cells());
    }
}
