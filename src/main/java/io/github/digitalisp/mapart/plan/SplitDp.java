package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Plan;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.RestockPlanner.Site;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Result;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Round;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;

/**
 * The search behind {@link SplitRestockPlanner}: a forward DP over the restock rounds of the rest of the tile, the
 * Split procedure (Beasley 1983, Prins 2004) run over the
 * engine's replan order. One instance per request; not thread-safe.
 *
 * <p><b>Rows.</b> A lane's placements grouped by world z, indexed by z ascending.
 *
 * <p><b>State</b> {@code (i, lo, hi, x, z)}: lanes before {@code i} are built, of lane {@code i} only rows
 * {@code lo..hi} remain, later lanes are untouched, the bot stands at block {@code (x, z)}. States with the same rows
 * form a <i>group</i>. A group is scanned once for all its positions: only the walk to the first chest depends on the
 * position, so the scan keeps the cheapest {@code f + trip} over the positions and recomputes it only when the extent
 * of the withdrawn items' depots changes. Groups are processed by remaining cells, descending: every round places at
 * least one cell, so that order is topological.
 *
 * <p><b>Round from a state.</b> The engine re-plans the lanes before every load: lane {@code i}'s rows {@code lo..hi}
 * N→S, then lane {@code i+1} S→N, {@code i+2} N→S, and so on. A round may end after any row (lane ends included);
 * the next state keeps the rows not walked and stands one row past the last row in walking direction. Slots are
 * {@code Σ ceil(n/stack)} over the round's items (carried partial stacks ignored), capacity is the first round's.
 *
 * <p><b>Round 1</b> walks exactly the given lanes and placements and counts the held items exactly (top-up or whole
 * stacks, held slots). It may also end at the max-fill cell, mid-row, and at the last cell the carried items cover;
 * the next state then keeps the whole cut row. A round 1 that withdraws nothing and does not finish the tile is a
 * candidate only when no round 1 with a withdrawal fits, and then ends only at a lane end or at that carried max-fill:
 * later rounds ignore carried partial stacks, so a short withdraw-free round would look like it sheds them, and the
 * next replan would plan the same short round again.
 *
 * <p><b>Carried-stacks guard</b> ({@link #solve(Request, boolean, boolean)}, {@code build.restockCarriedGuard}): the
 * same holds for a short round 1 that withdraws: when the stacks carried out of it that the rest of the path still uses
 * leave round 2 no room ({@link #launders}; split partial stacks count each slot, {@link #carriedSlots}), the search
 * runs again with round 1 ending at its max fill at the earliest, and that path wins unless it is estimated dearer than
 * the first by more than one restock.
 *
 * <p><b>Two-lane rule</b> ({@code lanesFirst}; the roadmap's rank 1 with item 15 as its fallback): when the round can
 * end at the end of its second lane (the current lane and the next both done), only lane ends are candidates;
 * otherwise every cut is, as in SPLIT. A state with one lane left has no pair: every cut counts. Slots only grow along
 * a round, so "a lane end finishing two lanes fits" is "the second lane end fits".
 *
 * <p><b>Load direction</b> ({@link Site#flipLanes}, {@code build.restockLaneFlip}): every round may also walk its lanes
 * the other way, the state's lane S→N (rows {@code hi..lo}), then
 * lane {@code i+1} N→S, and so on; round 1 then walks the request's lanes flipped ({@link LaneFlip}), the same cells
 * with the rows in reverse order. A cut in that first lane leaves its rows {@code lo..r−1}. The engine runs the plan's
 * direction ({@link Plan#flipped}) and plans the next load from its replan again. Ties go to the N→S direction (scanned
 * first). Both directions double the search; the work guard keeps the same cut steps.
 *
 * <p><b>Work guard.</b> Rounds that end inside the first lane of a prefix-derived group (rows {@code lo..hi} with
 * {@code hi} short of the lane's last row) create {@code O(rows²)} interval states per lane. On a full tile in SPLIT
 * mode that is about 120,000 groups for 9-wide lanes; each is scanned for a whole load. The search therefore allows
 * cuts only at row boundaries {@code b} with {@code b % rowStep == 0} (prefix, suffix and lane-change cuts) and
 * {@code b % intervalStep == 0} (cuts creating intervals), chosen so the estimated work (groups × rows per load) stays
 * under {@link #BUDGET} row steps; intervals are coarsened first. Lane ends and every round-1 candidate are always
 * kept. Small inputs keep both steps at 1: the search is then exact over the model. A run that overshoots
 * {@link #HARD_LIMIT} anyway is aborted and repeated coarser.
 */
final class SplitDp {
    /** Costs within this are ties. */
    static final double TOL = 1e-9;
    /** Estimated row steps (rows added to scanned rounds) up to which every interval cut is kept. */
    static final long INTERVAL_BUDGET = 1_500_000L;
    /** Estimated row steps up to which every row cut is kept: about 30 ms on a desktop JIT (15 ns per row step). */
    static final long BUDGET = 3_000_000L;
    /** Row steps after which a run is aborted and repeated with coarser cuts. */
    static final long HARD_LIMIT = 4 * BUDGET;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final int DEFAULT_STACK = 64;
    private static final int INITIAL = 0;
    private static final int TERMINAL = 1;

    private final boolean lanesFirst;
    private final boolean hasSite;
    private final double walkSpeed;
    private final double legSec;
    private final double unwind;
    private final double chest;
    private final double click;
    private final double laneRun;

    // ---- lanes (model lanes: the request's lanes that have placements) ----
    private final int reqLanes;
    private final int lanes;
    private final int[] reqIndex;
    private final int[] centre;
    private final boolean[] ownSouth;
    private final int[] rowsOf;
    private final int[] rowBase;
    private final int maxRows;
    private final int totalRows;
    private final int[] rowZ;
    private final int[] rowCells;
    private final int[] rowPair;
    private final int[] pairItem;
    private final int[] pairCount;
    private final int[][] cumCells;
    private final int[] laneCells;
    private final int[] cellsAfter;
    private final int totalCells;
    private final int[][] cellItem;
    private final int[][] cellRow;

    // ---- items (indexed in first-need order) ----
    private final int items;
    private final String[] itemId;
    private final int[] stack;
    private final int[] held;
    private final int[] heldU;
    private final boolean[] known;
    private final int[] depX;
    private final int[] depZ;
    private final int[] proj;
    private final int baseSlots;
    private final int cap;
    private final int reserve;
    private final boolean topUp;
    private final int botX;
    private final int botZ;
    private final int startX;
    private final int startZ;
    /** {@link Site#flipLanes}: rounds are also scanned with their first lane S→N. */
    private final boolean flip;
    /** Round 1 walked the other way: the request's lanes flipped ({@link LaneFlip}), as {@link #cellItem} etc. */
    private final int[][] cellItemF;
    private final int[][] cellRowF;
    private final boolean[] ownSouthF;
    private final int startXF;
    private final int startZF;

    // ---- search ----
    private int rowStep = 1;
    private int intervalStep = 1;
    /** Boundary {@code b} (between rows {@code b−1} and {@code b}) is a candidate: {@code b % rowStep == 0}. */
    private boolean[] rowCut;
    /** As {@link #rowCut} for cuts that leave an interval: {@code b % intervalStep == 0}. */
    private boolean[] intervalCut;
    private long rowSteps;
    private int nStates;
    private int nGroups;
    private int[] sGroup;
    private int[] sX;
    private int[] sZ;
    private int[] sBack;
    private int[] sLen;
    private int[] sFirst;
    private int[] sLast;
    private int[] sNext;
    private double[] sF;
    private int[] gLane;
    private int[] gLo;
    private int[] gHi;
    private int[] gHead;
    private int[] gNextBucket;
    private int[] bucketHead;
    private long[] mapKeys;
    private int[] mapVals;
    private int mapSize;
    private int[][] cutSouth;
    private int[][] cutNorth;
    private int[] endSouth;
    private int[] endNorth;
    private int[][][] firstCut;
    private int[][] firstEnd;
    /** As {@link #firstCut} / {@link #firstEnd} for a first lane walked S→N, keyed by its first remaining row. */
    private int[][][] firstCutN;
    private int[][] firstEndN;
    /** Round 1 ends at this many cells at the earliest ({@link #solve(Request, boolean, boolean)}); 0 = anywhere. */
    private int round1Min;
    /** Round-1 candidates allowed: {cells, 1 = walked the other way}. */
    private final List<int[]> round1Allowed = new ArrayList<>();
    /** The round into each state walked its first lane S→N ({@link #flip}). */
    private boolean[] sUp;

    // ---- one round's scan ----
    private final int[] cnt;
    private final int[] room;
    private final int[] touched;
    private int nTouched;
    private int slots;
    private int wCount;
    private int loItem;
    private int hiItem;
    private int curGroup;
    private int curOnly;
    private int bX;
    private int bZ;
    private boolean baseDirty;
    private double minBase;
    private int minState;
    private int nBuf;
    private int[] bufLane = new int[64];
    private int[] bufRow = new int[64];
    private int[] bufRows = new int[64];
    private int[] bufCells = new int[64];
    private int[] bufSrc = new int[64];
    private double[] bufV = new double[64];
    /** The scanned round walks its first lane S→N. */
    private boolean scanUp;

    SplitDp(Request req, boolean lanesFirst) {
        this.lanesFirst = lanesFirst;
        Site site = req.site();
        hasSite = site != null;
        Costs c = site == null || site.costs() == null ? Costs.DEFAULT : site.costs();
        walkSpeed = c.walkSpeed();
        legSec = c.legSeconds();
        unwind = c.unwindSeconds();
        chest = c.chestSeconds();
        click = c.clickSeconds();
        laneRun = c.laneRunSeconds();
        topUp = req.topUp();
        reserve = Math.max(0, req.lastLoadReserve());

        List<Lane> all = req.remaining();
        reqLanes = all.size();
        int[] ri = new int[reqLanes];
        int nl = 0;
        for (int q = 0; q < reqLanes; q++) if (!all.get(q).placements().isEmpty()) ri[nl++] = q;
        lanes = nl;
        reqIndex = Arrays.copyOf(ri, nl);

        Map<String, Integer> index = new HashMap<>();
        List<String> names = new ArrayList<>();
        cellItem = new int[lanes][];
        cellRow = new int[lanes][];
        centre = new int[lanes];
        ownSouth = new boolean[lanes];
        rowsOf = new int[lanes];
        rowBase = new int[lanes + 1];
        laneCells = new int[lanes];
        int[][] laneZ = new int[lanes][];
        int nRows = 0;
        int total = 0;
        int widest = 0;
        for (int j = 0; j < lanes; j++) {
            Lane lane = all.get(reqIndex[j]);
            List<Placement> ps = lane.placements();
            int n = ps.size();
            int[] it = new int[n];
            int[] zs = new int[n];
            for (int t = 0; t < n; t++) {
                Placement p = ps.get(t);
                zs[t] = p.pos().z();
                String id = p.itemId();
                if (id == null) {
                    it[t] = -1;
                } else {
                    Integer k = index.get(id);
                    if (k == null) {
                        k = names.size();
                        index.put(id, k);
                        names.add(id);
                    }
                    it[t] = k;
                }
            }
            int[] sorted = zs.clone();
            Arrays.sort(sorted);
            int u = 0;
            for (int t = 0; t < n; t++) if (t == 0 || sorted[t] != sorted[t - 1]) sorted[u++] = sorted[t];
            int[] rz = Arrays.copyOf(sorted, u);
            int[] rows = new int[n];
            for (int t = 0; t < n; t++) rows[t] = Arrays.binarySearch(rz, zs[t]);
            cellItem[j] = it;
            cellRow[j] = rows;
            laneZ[j] = rz;
            rowsOf[j] = u;
            widest = Math.max(widest, u);
            rowBase[j] = nRows;
            nRows += u;
            laneCells[j] = n;
            total += n;
            centre[j] = lane.from().x();
            int fz = lane.from().z();
            int tz = lane.to().z();
            ownSouth[j] = tz != fz ? tz > fz : zs[n - 1] >= zs[0];
        }
        rowBase[lanes] = nRows;
        totalRows = nRows;
        maxRows = widest;
        totalCells = total;
        items = names.size();
        itemId = names.toArray(new String[0]);

        rowZ = new int[nRows];
        rowCells = new int[nRows];
        rowPair = new int[nRows + 1];
        cumCells = new int[lanes][];
        int[] pi = new int[Math.max(16, total)];
        int[] pc = new int[pi.length];
        int np = 0;
        for (int j = 0; j < lanes; j++) {
            int rj = rowsOf[j];
            int rb = rowBase[j];
            int[] counts = new int[rj * Math.max(1, items)];
            for (int t = 0; t < cellItem[j].length; t++) {
                int r = cellRow[j][t];
                rowCells[rb + r]++;
                if (cellItem[j][t] >= 0) counts[r * items + cellItem[j][t]]++;
            }
            int[] cum = new int[rj + 1];
            for (int r = 0; r < rj; r++) {
                rowZ[rb + r] = laneZ[j][r];
                rowPair[rb + r] = np;
                for (int k = 0; k < items; k++) {
                    int n = counts[r * items + k];
                    if (n > 0) {
                        pi[np] = k;
                        pc[np] = n;
                        np++;
                    }
                }
                cum[r + 1] = cum[r] + rowCells[rb + r];
            }
            cumCells[j] = cum;
        }
        rowPair[nRows] = np;
        pairItem = Arrays.copyOf(pi, np);
        pairCount = Arrays.copyOf(pc, np);
        cellsAfter = new int[lanes];
        for (int j = lanes - 2; j >= 0; j--) cellsAfter[j] = cellsAfter[j + 1] + laneCells[j + 1];

        ToIntFunction<String> ms = req.maxStack();
        stack = new int[items];
        for (int k = 0; k < items; k++) stack[k] = stackOf(ms, itemId[k]);
        held = new int[items];
        heldU = new int[items];
        int bs = 0;
        for (Map.Entry<String, Integer> e : req.held().entrySet()) {
            int h = e.getValue();
            if (h <= 0) continue;
            String id = e.getKey();
            int need = ceilDiv(h, stackOf(ms, id));
            Integer hs = req.heldSlots().get(id);
            int u = Math.max(hs == null ? need : hs, need);
            bs += u;
            Integer k = index.get(id);
            if (k != null) {
                held[k] = h;
                heldU[k] = u;
            }
        }
        baseSlots = bs;
        cap = Math.max(0, req.freeSlots()) + bs;

        known = new boolean[items];
        depX = new int[items];
        depZ = new int[items];
        proj = new int[items];
        if (site != null && site.depot() != null) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int k = 0; k < items; k++) {
                Pos p = site.depot().apply(itemId[k]);
                if (p == null) continue;
                known[k] = true;
                depX[k] = p.x();
                depZ[k] = p.z();
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
            boolean xAxis = (long) maxX - minX >= (long) maxZ - minZ;
            for (int k = 0; k < items; k++) proj[k] = xAxis ? depX[k] : depZ[k];
        }
        Pos bot = site == null ? null : site.bot();
        botX = bot == null ? 0 : bot.x();
        botZ = bot == null ? 0 : bot.z();
        Pos from = lanes == 0 ? null : all.get(reqIndex[0]).from();
        startX = from == null ? 0 : from.x();
        startZ = from == null ? 0 : from.z();

        flip = site != null && site.flipLanes() && lanes > 0;
        if (flip) {
            cellItemF = new int[lanes][];
            cellRowF = new int[lanes][];
            ownSouthF = new boolean[lanes];
            for (int j = 0; j < lanes; j++) {
                Lane lane = LaneFlip.flip(all.get(reqIndex[j]));
                List<Placement> ps = lane.placements();
                int n = ps.size();
                int[] it = new int[n];
                int[] rows = new int[n];
                int[] rz = Arrays.copyOfRange(rowZ, rowBase[j], rowBase[j] + rowsOf[j]);
                for (int t = 0; t < n; t++) {
                    Placement p = ps.get(t);
                    it[t] = p.itemId() == null ? -1 : index.get(p.itemId());
                    rows[t] = Arrays.binarySearch(rz, p.pos().z());
                }
                cellItemF[j] = it;
                cellRowF[j] = rows;
                int fz = lane.from().z();
                int tz = lane.to().z();
                ownSouthF[j] = tz != fz ? tz > fz : ps.get(n - 1).pos().z() >= ps.get(0).pos().z();
            }
            Pos f = LaneFlip.flip(all.get(reqIndex[0])).from();
            startXF = f.x();
            startZF = f.z();
        } else {
            cellItemF = null;
            cellRowF = null;
            ownSouthF = null;
            startXF = 0;
            startZF = 0;
        }

        cnt = new int[items];
        room = new int[items];
        touched = new int[items];
    }

    // =================================================================================================================
    // Driver
    // =================================================================================================================

    /** The optimal path for {@code req} (see the class comment for the model and the work guard). */
    static Result solve(Request req, boolean lanesFirst) {
        return solve(req, lanesFirst, false);
    }

    /**
     * As {@link #solve(Request, boolean)}; with {@code carriedGuard} a round 1 cut short of its max fill on a round 2
     * that the carried stacks would not leave room for ({@link #launders}) gives way to the max fill: the search runs
     * again with round 1 ending there at the earliest. Live 2026-10-31 (map "haruhi", lanes 6-7, carried partial stacks
     * the rest of the band uses): "load covers 0 + 9 cells, withdraw {birch_pressure_plate=64}" (planned: 3 loads
     * left) six times in a row, a run of 9 to 108 cells a trip, where the load as carried covered 650 to 780 cells:
     * every replan saw the same round 2 the model gave room the carried stacks take.
     */
    static Result solve(Request req, boolean lanesFirst, boolean carriedGuard) {
        int[] fill = new int[1];
        Result first = solveOnce(req, lanesFirst, 0, fill);
        if (!carriedGuard || !first.complete() || first.rounds().size() < 2 || !launders(req, first.rounds()))
            return first;
        if (first.rounds().getFirst().cells() >= fill[0]) return first;
        Result filled = solveOnce(req, lanesFirst, fill[0], new int[1]);
        if (!filled.complete() || filled.rounds().isEmpty()) return first;
        // the laundering path costs at least one more restock than it says (round 2 does not fit, the replan runs
        // round 1 again): the max fill wins unless it costs more than that restock (the restock model with 28-30
        // slots: forcing every one cost up to 2.6 % where the DP's cut was only a little short)
        return filled.seconds() <= first.seconds() + restockSeconds(first.rounds().get(1)) ? filled : first;
    }

    /** The fixed part of a round: the trip to the chests and back, the unwind and the chest visit. */
    private static double restockSeconds(Round r) {
        return r.tripSeconds() + r.unwindSeconds() + r.chestSeconds();
    }

    /**
     * The search's round 2 does not fit in reality: the stacks carried out of round 1 (held, plus the withdrawal, less
     * round 1's cells) that the rest of the path still uses take their slots in it too (the later-round model counts
     * only its own cells), so the next replan cannot run it and plans round 1 again. The carried items take the slots
     * {@link #carriedSlots} counts, a round-2 withdrawal of one at least as many ({@code WithdrawMode.TOP_UP} fills the
     * partial stacks, a shift-click merges into them: {@code max(slots carried, ceil(total / stack))}).
     */
    static boolean launders(Request req, List<Round> rounds) {
        if (rounds.size() < 2) return false;
        Round r1 = rounds.get(0);
        Round r2 = rounds.get(1);
        Set<String> later = new HashSet<>();
        for (int i = 1; i < rounds.size(); i++) later.addAll(rounds.get(i).items().keySet());
        Set<String> ids = new TreeSet<>(r2.items().keySet());
        ids.addAll(req.held().keySet());
        ids.addAll(r1.withdraw().keySet());
        boolean topUp = req.topUp();
        long used = 0;
        for (String id : ids) {
            int s = stackOf(req.maxStack(), id);
            int h = Math.max(0, req.held().getOrDefault(id, 0));
            int w = r1.withdraw().getOrDefault(id, 0);
            long c = (long) h + w - r1.items().getOrDefault(id, 0);
            if (c <= 0 || !later.contains(id)) c = 0; // used up, or a leftover the restock drops
            long n = r2.items().getOrDefault(id, 0);
            long total = n <= c ? c : topUp ? (long) s * ceilDiv((int) n, s) : c + (long) s * ceilDiv((int) (n - c), s);
            long cu = c == 0 ? 0 : carriedSlots(h, heldSlotsOf(req, id, h, s), w, c, s);
            used += Math.max(cu, (total + s - 1) / s);
        }
        return used > r2.capacity();
    }

    /**
     * Slots {@code c > 0} items carry out of round 1: whole stacks, plus the split partial stacks round 1 did not
     * fill. The inventory holds {@code h} in {@code u} slots, {@code u − ceil(h / s)} of them more than whole stacks
     * (the lanes' hotbar moves leave a colour in two partial stacks: 45 moss in 2 slots). Round 1's withdrawal
     * {@code w} fills them ({@code WithdrawMode.TOP_UP}) only as far as {@code ceil((h + w) / s)} slots reach; the rest
     * stay split, at most one slot per carried item. Nothing split: {@code ceil(c / s)}, as the later-round model.
     * Local 10x colour suppression run 2026-11-14 (the merged noobline row's moss, 45 in 2 slots): the capacity
     * counted its 2 slots, the guard 1 ({@code ceil(45 / 64)}), so round 2 "fit" with no slot to spare and a load of 7
     * cells stood; the split-aware count gives way to the max fill (1,071 cells on that run's lanes).
     */
    static long carriedSlots(int h, int u, int w, long c, int s) {
        long split = Math.max(0, u - ((long) h + w + s - 1) / s);
        return Math.min(c, (c + s - 1) / s + split);
    }

    /** Slots {@code h} items of {@code id} occupy now ({@link Request#heldSlots}, at least whole stacks). */
    private static int heldSlotsOf(Request req, String id, int h, int s) {
        int need = ceilDiv(h, s);
        Integer hs = req.heldSlots().get(id);
        return hs == null ? need : Math.max(hs, need);
    }

    /**
     * One search; round 1 ends at {@code round1Min} cells at the earliest. {@code fill[0]} gets the longest round 1
     * the search allowed (its max fill).
     */
    private static Result solveOnce(Request req, boolean lanesFirst, int round1Min, int[] fill) {
        SplitDp dp = new SplitDp(req, lanesFirst);
        dp.round1Min = round1Min;
        if (dp.totalCells == 0) {
            return new Result(new Plan(Map.of(), dp.reqLanes, 0, true, 0, 0.0), List.of(), true,
                new Stats(0, 0, 0, 1, 1, 0));
        }
        int load = dp.probeLoadRows();
        int[] k = {1, 1};
        dp.coarsen(k, load, false);
        int attempts = 0;
        while (true) {
            attempts++;
            boolean last = k[0] >= dp.maxRows;
            // both directions scan every group twice: the same cut steps, twice the work
            if (dp.run(k[0], k[1], last ? Long.MAX_VALUE : HARD_LIMIT * (dp.flip ? 2 : 1))) break;
            dp.coarsen(k, load, true);
        }
        for (int[] c : dp.round1Allowed) fill[0] = Math.max(fill[0], c[0]);
        return dp.result(attempts);
    }

    /** As {@link #solve(Request, boolean)} with fixed cut steps and no work limit (tests: the guard against exact). */
    static Result solve(Request req, boolean lanesFirst, int rowStep, int intervalStep) {
        SplitDp dp = new SplitDp(req, lanesFirst);
        if (dp.totalCells == 0) return solve(req, lanesFirst);
        dp.run(Math.max(1, rowStep), Math.max(1, intervalStep), Long.MAX_VALUE);
        return dp.result(1);
    }

    /** Rows a load covers from the start of lane 0 and of the middle lane (the larger), for the work estimate. */
    private int probeLoadRows() {
        int best = 1;
        for (int start : new int[] {0, lanes / 2}) {
            resetScan(-1, -1, 0, 0);
            int rows = 0;
            boolean south = true;
            outer:
            for (int j = start; j < lanes; j++) {
                for (int w = 0; w < rowsOf[j]; w++) {
                    addRow(rowBase[j] + (south ? w : rowsOf[j] - 1 - w));
                    if (slots > cap) break outer;
                    rows++;
                }
                south = !south;
            }
            best = Math.max(best, rows);
        }
        resetScan(-1, -1, 0, 0);
        rowSteps = 0;
        return best;
    }

    /**
     * Coarsens the cut steps {@code k = {rowStep, intervalStep}} until the estimated work fits: interval cuts first (to
     * {@link #INTERVAL_BUDGET}; on full tiles they never changed the optimum), row cuts only beyond {@link #BUDGET}.
     * {@code more}: at least one step coarser than now (a run overshot {@link #HARD_LIMIT}).
     */
    private void coarsen(int[] k, int load, boolean more) {
        if (more) {
            if (k[1] < maxRows) k[1] = maxRows;
            else k[0] = Math.min(maxRows, k[0] * 2);
        }
        while (k[1] < maxRows && estimate(k[0], k[1], load) > INTERVAL_BUDGET) k[1] = Math.min(maxRows, k[1] * 2);
        while (k[0] < maxRows && estimate(k[0], k[1], load) > BUDGET) k[0] = Math.min(maxRows, k[0] * 2);
    }

    /** Estimated row steps with cut steps {@code kp} (rows) and {@code ki} (intervals): groups × rows per load. */
    private long estimate(int kp, int ki, int load) {
        long groups = 0;
        for (int j = 0; j < lanes; j++) {
            long bp = (rowsOf[j] + kp - 1) / kp;
            long bi = (rowsOf[j] + ki - 1) / ki;
            groups += 1 + 2 * bp + bp * bi / 2;
        }
        return groups * Math.max(1, Math.min(load, totalRows));
    }

    /** One search with the given cut steps. False when it overshot {@code limit} row steps (aborted). */
    private boolean run(int kp, int ki, long limit) {
        rowStep = kp;
        intervalStep = ki;
        rowCut = new boolean[maxRows + 1];
        intervalCut = new boolean[maxRows + 1];
        for (int b = 0; b <= maxRows; b++) {
            rowCut[b] = b % kp == 0;
            intervalCut[b] = b % ki == 0;
        }
        rowSteps = 0;
        nStates = 0;
        nGroups = 0;
        int cap0 = 1024;
        sGroup = new int[cap0];
        sX = new int[cap0];
        sZ = new int[cap0];
        sBack = new int[cap0];
        sLen = new int[cap0];
        sFirst = new int[cap0];
        sLast = new int[cap0];
        sNext = new int[cap0];
        sF = new double[cap0];
        sUp = new boolean[cap0];
        gLane = new int[cap0];
        gLo = new int[cap0];
        gHi = new int[cap0];
        gHead = new int[cap0];
        gNextBucket = new int[cap0];
        bucketHead = new int[totalCells + 1];
        Arrays.fill(bucketHead, -1);
        mapKeys = new long[2048];
        mapVals = new int[2048];
        Arrays.fill(mapVals, -1);
        mapSize = 0;
        cutSouth = new int[lanes][];
        cutNorth = new int[lanes][];
        endSouth = new int[lanes];
        endNorth = new int[lanes];
        Arrays.fill(endSouth, -1);
        Arrays.fill(endNorth, -1);
        firstCut = new int[lanes][][];
        firstEnd = new int[lanes][];
        firstCutN = new int[lanes][][];
        firstEndN = new int[lanes][];
        round1Allowed.clear();

        int init = newState(-1, botX, botZ);
        int term = newState(-1, 0, 0);
        assert init == INITIAL && term == TERMINAL;
        sF[INITIAL] = 0;
        sFirst[INITIAL] = 0;
        round1(-1, null, false);
        if (flip) round1(-1, null, true);
        for (int rem = totalCells; rem > 0; rem--) {
            for (int g = bucketHead[rem]; g >= 0; g = gNextBucket[g]) {
                if (rowSteps > limit) return false;
                if (!anyReached(g)) continue;
                scanGroup(g, -1, 0, null, false);
                if (flip) scanGroup(g, -1, 0, null, true);
            }
        }
        return true;
    }

    private Result result(int attempts) {
        Stats stats = new Stats(nStates, nGroups, rowSteps, rowStep, intervalStep, attempts);
        if (sF[TERMINAL] == INF) {
            // No complete path (a row or the last load that can never fit). Best effort: the longest allowed round 1.
            // (N→S first on equal length)
            int best = -1;
            boolean bestUp = false;
            for (int[] c : round1Allowed) {
                if (c[0] > best) {
                    best = c[0];
                    bestUp = c[1] != 0;
                }
            }
            if (best <= 0) return new Result(new Plan(Map.of(), 0, 0, true, 0, 0.0), List.of(), false, stats);
            RoundOut o = new RoundOut();
            round1(best, o, bestUp);
            Round r1 = o.toRound();
            return new Result(planOf(r1, 0, 0.0), List.of(r1), false, stats);
        }
        List<int[]> edges = new ArrayList<>();
        for (int t = TERMINAL; t != INITIAL; t = sBack[t]) edges.add(new int[] {sBack[t], sLen[t], sUp[t] ? 1 : 0});
        Collections.reverse(edges);
        List<Round> rounds = new ArrayList<>(edges.size());
        for (int[] e : edges) {
            RoundOut o = new RoundOut();
            if (e[0] == INITIAL) round1(e[1], o, e[2] != 0);
            else scanGroup(sGroup[e[0]], e[0], e[1], o, e[2] != 0);
            rounds.add(o.toRound());
        }
        return new Result(planOf(rounds.get(0), rounds.size(), sF[TERMINAL]), List.copyOf(rounds), true, stats);
    }

    /** The plan of round 1: covered lanes and cells in walking order (its own direction), the exact withdrawal. */
    private Plan planOf(Round first, int rounds, double seconds) {
        int left = first.cells();
        int a = 0;
        while (a < lanes && left >= laneCells[a]) {
            left -= laneCells[a];
            a++;
        }
        int covered = a < lanes ? reqIndex[a] : reqLanes;
        int partial = a < lanes ? left : 0;
        return new Plan(first.withdraw(), covered, partial, true, rounds, seconds, first.flipped());
    }

    // =================================================================================================================
    // Round 1: the given lanes and placements, held items counted exactly
    // =================================================================================================================

    /**
     * Round 1. {@code out == null}: relax every candidate end (row ends, lane ends, the max-fill cell). Else: describe
     * the round that covers the first {@code stop} placements into {@code out}. {@code up}: the lanes walked the other
     * way ({@link #flip}).
     */
    private void round1(int stop, RoundOut out, boolean up) {
        int[][] cellItem = up ? cellItemF : this.cellItem;
        int[][] cellRow = up ? cellRowF : this.cellRow;
        boolean[] ownSouth = up ? ownSouthF : this.ownSouth;
        int startX = up ? startXF : this.startX;
        int startZ = up ? startZF : this.startZ;
        int[] n = new int[items];
        int[] sl = heldU.clone();
        int[] cl = new int[items];
        boolean[] inW = new boolean[items];
        int used = baseSlots;
        int clicks = 0;
        int w = 0;
        loItem = -1;
        hiItem = -1;
        double trip = 0;
        boolean dirty = true;
        int cells = 0;
        int parts = 0;
        double changes = 0;
        int px = 0;
        int pz = 0;
        List<double[]> cands = new ArrayList<>();
        double[] pending = null;
        double[] carried = null;
        outer:
        for (int a = 0; a < lanes; a++) {
            boolean south = ownSouth[a];
            int rb = rowBase[a];
            if (a == 0) {
                parts = 1;
            } else {
                int sz = south ? rowZ[rb] - 1 : rowZ[rb + rowsOf[a] - 1] + 1;
                changes += unwind + leg(dist(px, pz, centre[a], sz));
                parts++;
            }
            int[] ci = cellItem[a];
            int[] cr = cellRow[a];
            int size = ci.length;
            for (int t = 0; t < size; t++) {
                int k = ci[t];
                if (k >= 0) {
                    int nk = ++n[k];
                    int ns = slots1(k, nk);
                    int nc = clicks1(k, nk);
                    used += ns - sl[k];
                    sl[k] = ns;
                    clicks += nc - cl[k];
                    cl[k] = nc;
                    if (!inW[k] && nk > held[k]) {
                        inW[k] = true;
                        w++;
                        if (known[k] && extend(k)) dirty = true;
                    }
                }
                cells++;
                boolean laneEnd = t == size - 1;
                boolean terminal = laneEnd && a == lanes - 1;
                if (used > (terminal ? cap - reserve : cap)) break outer;
                if (dirty) {
                    trip = trip(botX, botZ, startX, startZ);
                    dirty = false;
                }
                double v = trip + unwind + chest * w + click * clicks + changes + laneRun * parts;
                if (out != null) {
                    if (cells == stop) {
                        out.round1(this, a, t, cells, n, used, terminal, clicks, w, trip, changes, parts, v, up);
                        return;
                    }
                    continue;
                }
                boolean rowEnd = laneEnd || cr[t + 1] != cr[t];
                double[] cand = {cells, v, a, t, laneEnd ? 1 : 0, w};
                if (w == 0) carried = cand;
                if (rowEnd) {
                    cands.add(cand);
                    pending = null;
                } else {
                    pending = cand;
                }
            }
            px = centre[a];
            pz = rowZ[rb + cr[size - 1]] + (south ? 1 : -1);
        }
        if (out != null) throw new IllegalStateException("round 1 of " + stop + " cells does not fit");
        if (pending != null) cands.add(pending);
        if (carried != null && !cands.contains(carried)) cands.add(carried);
        // A load that withdraws nothing and does not finish the tile is planned only when no load with a withdrawal
        // fits at all (the inventory is full of what the next cells use), and then it ends at a lane end or where the
        // carried items run out, never at another row: the later-round model ignores carried stacks, so a short
        // withdraw-free load would "launder" them, and every such load is a restock round of its own (local 10x run:
        // "load covers 0 + 78 cells, withdraw {}" between two full loads).
        int carriedCells = carried == null ? -1 : (int) carried[0];
        boolean anyWithdraw = false;
        for (double[] c : cands) anyWithdraw |= c[5] > 0;
        boolean withdrawFits = anyWithdraw;
        cands.removeIf(c -> c[5] == 0 && !(c[4] > 0 && (int) c[2] == lanes - 1)
            && (withdrawFits || (c[4] == 0 && (int) c[0] != carriedCells)));
        boolean pairFits = false;
        for (double[] c : cands) pairFits |= c[4] > 0 && c[2] >= 1;
        for (double[] c : cands) {
            if (lanesFirst && pairFits && c[4] == 0) continue;
            int cellsC = (int) c[0];
            if (cellsC < round1Min) continue;
            round1Allowed.add(new int[] {cellsC, up ? 1 : 0});
            relax(target1((int) c[2], (int) c[3], cellRow, ownSouth), c[1], INITIAL, cellsC, cellsC, up);
        }
    }

    /** The state after round 1 ends with placement {@code t} of lane {@code a} (rows and directions of its walk). */
    private int target1(int a, int t, int[][] cellRow, boolean[] ownSouth) {
        int[] cr = cellRow[a];
        int rb = rowBase[a];
        int step = ownSouth[a] ? 1 : -1;
        int z = rowZ[rb + cr[t]] + step;
        if (t == cr.length - 1) {
            if (a == lanes - 1) return TERMINAL;
            return state(a + 1, 0, rowsOf[a + 1] - 1, centre[a], z);
        }
        int rho = cr[t + 1];
        return ownSouth[a] ? state(a, rho, rowsOf[a] - 1, centre[a], z) : state(a, 0, rho, centre[a], z);
    }

    /** Round-1 slots of item {@code k} with {@code n} cells planned (held slots count at least). */
    private int slots1(int k, int n) {
        int h = held[k];
        int u = heldU[k];
        int s = stack[k];
        if (n <= h) return u;
        if (topUp) return Math.max(u, ceilDiv(n, s));
        long t = h + (long) s * ceilDiv(n - h, s);
        return Math.max(u, (int) ((t + s - 1) / s));
    }

    /** Round-1 container clicks for item {@code k} with {@code n} cells planned. */
    private int clicks1(int k, int n) {
        int h = held[k];
        int s = stack[k];
        if (n <= h) return 0;
        if (!topUp) return ceilDiv(n - h, s);
        int t = s * ceilDiv(n, s);
        int r = h % s;
        return r > 0 ? 3 + (t - h - (s - r)) / s : (t - h) / s;
    }

    /** Round-1 withdrawal of item {@code k} with {@code n} cells planned. */
    int withdraw1(int k, int n) {
        int h = held[k];
        if (n <= h) return 0;
        return topUp ? stack[k] * ceilDiv(n, stack[k]) - h : n - h;
    }

    // =================================================================================================================
    // Later rounds
    // =================================================================================================================

    /**
     * Scans the round from group {@code g}. {@code out == null}: relax every allowed candidate end for all reached
     * states of the group. Else: describe the round of state {@code only} that covers {@code stopRows} rows. {@code up}:
     * the round walks its first lane S→N (rows {@code hi..lo}) and the next lanes N→S, S→N, … ({@link #flip}).
     *
     * <p>Two-lane rule ({@code lanesFirst}): cuts inside the round's first two lanes are held back until the second
     * lane's end. When that end fits (the lane pair fits) they are dropped and only lane ends count; otherwise they are
     * relaxed like in SPLIT. A state with one lane left has no pair: every cut counts.
     */
    private void scanGroup(int g, int only, int stopRows, RoundOut out, boolean up) {
        int i = gLane[g];
        int lo = gLo[g];
        int hi = gHi[g];
        int base = rowBase[i];
        scanUp = up;
        resetScan(g, only, centre[i], up ? rowZ[base + hi] + 1 : rowZ[base + lo] - 1);
        nBuf = 0;
        boolean hold = out == null && lanesFirst && i < lanes - 1;
        boolean direct = out == null && !hold;
        int rows = 0;
        int cells = 0;
        int parts = 1;
        double changes = 0;
        // a cut in the first lane leaves a suffix (N→S) or a prefix (S→N) of its rows, or an interval
        boolean[] cut1 = (up ? lo == 0 : hi == rowsOf[i] - 1) ? rowCut : intervalCut;
        for (int q = 0; q <= hi - lo; q++) {
            int r = up ? hi - q : lo + q;
            boolean end = q == hi - lo;
            addRow(base + r);
            rows++;
            if (slots > cap) {
                flushHeld(i, lo, hi);
                return;
            }
            cells += rowCells[base + r];
            boolean terminal = end && i == lanes - 1;
            if (out != null) {
                if (rows == stopRows) {
                    int[] to = !end ? (up ? new int[] {i, lo, r - 1} : new int[] {i, r + 1, hi})
                        : terminal ? null : new int[] {i + 1, 0, rowsOf[i + 1] - 1};
                    out.later(this, g, only, to, cells, terminal, changes, parts, up);
                    return;
                }
                continue;
            }
            if (!end) {
                if (cut1[up ? r : r + 1]) {
                    double v = cost(changes, parts);
                    if (hold) buffer(-1, r, rows, cells, v);
                    else relax(firstCutTarget(i, lo, hi, r, up), v, minState, rows, cells, up);
                }
            } else if (terminal) {
                if (slots <= cap - reserve) relax(TERMINAL, cost(changes, parts), minState, rows, cells, up);
                return;
            } else {
                double v = cost(changes, parts);
                relax(firstEndTarget(i, lo, hi, up), v, minState, rows, cells, up);
            }
        }
        int px = centre[i];
        int pz = up ? rowZ[base + lo] - 1 : rowZ[base + hi] + 1;
        boolean south = up;
        for (int j = i + 1; j < lanes; j++) {
            int rb = rowBase[j];
            int rj = rowsOf[j];
            int last = rj - 1;
            int sz = south ? rowZ[rb] - 1 : rowZ[rb + last] + 1;
            changes += unwind + leg(dist(px, pz, centre[j], sz));
            parts++;
            boolean held1 = hold && j == i + 1;
            int[] ct = direct ? cutArray(j, south) : null;
            for (int w = 0; w < last; w++) {
                int r = south ? w : last - w;
                int gr = rb + r;
                addRow(gr);
                rows++;
                if (slots > cap) {
                    flushHeld(i, lo, hi);
                    return;
                }
                cells += rowCells[gr];
                if (ct != null) {
                    if (rowCut[south ? r + 1 : r]) {
                        int t = ct[w];
                        if (t < 0) t = cutTarget(j, south, w);
                        double v = cost(changes, parts);
                        relax(t, v, minState, rows, cells, up);
                    }
                } else if (held1) {
                    if (rowCut[south ? r + 1 : r]) buffer(j, w, rows, cells, cost(changes, parts));
                } else if (out != null && rows == stopRows) {
                    out.later(this, g, only, south ? new int[] {j, r + 1, last} : new int[] {j, 0, r - 1}, cells, false,
                        changes, parts, up);
                    return;
                }
            }
            int r = south ? last : 0;
            addRow(rb + r);
            rows++;
            if (slots > cap) {
                flushHeld(i, lo, hi);
                return;
            }
            cells += rowCells[rb + r];
            boolean terminal = j == lanes - 1;
            if (out != null) {
                if (rows == stopRows) {
                    out.later(this, g, only, terminal ? null : new int[] {j + 1, 0, rowsOf[j + 1] - 1}, cells, terminal,
                        changes, parts, up);
                    return;
                }
            } else if (terminal) {
                if (slots <= cap - reserve) relax(TERMINAL, cost(changes, parts), minState, rows, cells, up);
                else flushHeld(i, lo, hi);
            } else {
                double v = cost(changes, parts);
                relax(laneEndTarget(j, south), v, minState, rows, cells, up);
            }
            if (held1) {
                // The lane pair fits: the held-back cuts are dropped, only lane ends from here on.
                nBuf = 0;
                hold = false;
            }
            px = centre[j];
            pz = south ? rowZ[rb + last] + 1 : rowZ[rb] - 1;
            south = !south;
        }
        if (out != null) throw new IllegalStateException("round of " + stopRows + " rows runs past the tile");
    }

    /** Two-lane rule, the lane pair does not fit: the held-back cuts become candidates. */
    private void flushHeld(int i, int lo, int hi) {
        for (int b = 0; b < nBuf; b++) {
            // lane i+1 walks S→N after a first lane N→S, N→S after one S→N
            int t = bufLane[b] < 0 ? firstCutTarget(i, lo, hi, bufRow[b], scanUp)
                : cutTarget(bufLane[b], scanUp, bufRow[b]);
            relax(t, bufV[b], bufSrc[b], bufRows[b], bufCells[b], scanUp);
        }
        nBuf = 0;
    }

    /** Holds back a cut: in the first lane after row {@code r} ({@code lane} −1) or in {@code lane} after walked row. */
    private void buffer(int lane, int r, int rows, int cells, double v) {
        if (nBuf == bufRow.length) {
            int n = nBuf * 2;
            bufLane = Arrays.copyOf(bufLane, n);
            bufRow = Arrays.copyOf(bufRow, n);
            bufRows = Arrays.copyOf(bufRows, n);
            bufCells = Arrays.copyOf(bufCells, n);
            bufSrc = Arrays.copyOf(bufSrc, n);
            bufV = Arrays.copyOf(bufV, n);
        }
        bufLane[nBuf] = lane;
        bufRow[nBuf] = r;
        bufRows[nBuf] = rows;
        bufCells[nBuf] = cells;
        bufSrc[nBuf] = minState;
        bufV[nBuf] = v;
        nBuf++;
    }

    /** Cost to here of ending the scanned round now: the cheapest position's f + trip, then the round's own terms. */
    private double cost(double changes, int parts) {
        if (baseDirty) recomputeBase();
        return minBase + unwind + chest * wCount + click * slots + changes + laneRun * parts;
    }

    private void recomputeBase() {
        baseDirty = false;
        if (curOnly >= 0) {
            minState = curOnly;
            minBase = sF[curOnly] + trip(sX[curOnly], sZ[curOnly], bX, bZ);
            return;
        }
        minBase = INF;
        minState = -1;
        for (int s = gHead[curGroup]; s >= 0; s = sNext[s]) {
            double f = sF[s];
            if (f == INF) continue;
            double v = f + trip(sX[s], sZ[s], bX, bZ);
            if (minState < 0 || v < minBase - TOL
                || (v <= minBase + TOL && (sFirst[s] > sFirst[minState] || (sFirst[s] == sFirst[minState] && s < minState)))) {
                minBase = v;
                minState = s;
            }
        }
    }

    private void resetScan(int g, int only, int bx, int bz) {
        for (int q = 0; q < nTouched; q++) {
            int k = touched[q];
            cnt[k] = 0;
            room[k] = 0;
        }
        nTouched = 0;
        slots = 0;
        wCount = 0;
        loItem = -1;
        hiItem = -1;
        curGroup = g;
        curOnly = only;
        bX = bx;
        bZ = bz;
        baseDirty = true;
    }

    /** Adds global row {@code gr} to the scanned round: counts, slots ({@code Σ ceil(n/stack)}), withdrawn items. */
    private void addRow(int gr) {
        rowSteps++;
        for (int p = rowPair[gr], e = rowPair[gr + 1]; p < e; p++) {
            int k = pairItem[p];
            int c = pairCount[p];
            if (cnt[k] == 0) {
                touched[nTouched++] = k;
                wCount++;
                if (known[k] && extend(k)) baseDirty = true;
            }
            cnt[k] += c;
            int rm = room[k];
            if (c <= rm) {
                room[k] = rm - c;
            } else {
                int s = stack[k];
                int over = c - rm;
                int add = (over + s - 1) / s;
                slots += add;
                room[k] = add * s - over;
            }
        }
    }

    /** Widens the depot extent by item {@code k} (ties: the smaller item index). True when an end moved. */
    private boolean extend(int k) {
        if (loItem < 0) {
            loItem = k;
            hiItem = k;
            return true;
        }
        boolean moved = false;
        int p = proj[k];
        if (p < proj[loItem] || (p == proj[loItem] && k < loItem)) {
            loItem = k;
            moved = true;
        }
        if (p > proj[hiItem] || (p == proj[hiItem] && k < hiItem)) {
            hiItem = k;
            moved = true;
        }
        return moved;
    }

    /**
     * Trip seconds from {@code (ax, az)} past the withdrawn items' depots to {@code (bx, bz)}: the sweep along the
     * depot axis in the cheaper direction (exact for collinear depots), 2 legs plus one per 5 blocks of span. Without
     * a known depot: one leg straight to the start.
     */
    private double trip(int ax, int az, int bx, int bz) {
        if (loItem < 0) return leg(dist(ax, az, bx, bz));
        int l = loItem;
        int h = hiItem;
        double span = dist(depX[l], depZ[l], depX[h], depZ[h]);
        double walk = Math.min(
            dist(ax, az, depX[l], depZ[l]) + span + dist(depX[h], depZ[h], bx, bz),
            dist(ax, az, depX[h], depZ[h]) + span + dist(depX[l], depZ[l], bx, bz));
        int legs = 2 + Math.abs(proj[h] - proj[l]) / 5;
        return legs * legSec + walk / walkSpeed;
    }

    private double leg(double d) {
        return d > 0 ? legSec + d / walkSpeed : 0;
    }

    private double dist(int ax, int az, int bx, int bz) {
        if (!hasSite) return 0;
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // =================================================================================================================
    // States and relaxation
    // =================================================================================================================

    /**
     * Relaxes state {@code t} with cost {@code v} from {@code src} by a round walking its first lane S→N when {@code up}.
     * Ties: the longer first round of the path (the load the plan returns), then the longer last round, then the smaller
     * source index, then the round relaxed first (N→S before S→N).
     */
    private void relax(int t, double v, int src, int len, int cells, boolean up) {
        double cur = sF[t];
        if (v > cur + TOL) return;
        int first = src == INITIAL ? cells : sFirst[src];
        if (v < cur - TOL || (v <= cur + TOL && (first > sFirst[t]
            || (first == sFirst[t] && (cells > sLast[t] || (cells == sLast[t] && src < sBack[t])))))) {
            sF[t] = v;
            sBack[t] = src;
            sLen[t] = len;
            sFirst[t] = first;
            sLast[t] = cells;
            sUp[t] = up;
        }
    }

    private boolean anyReached(int g) {
        for (int s = gHead[g]; s >= 0; s = sNext[s]) if (sF[s] < INF) return true;
        return false;
    }

    /**
     * After a cut inside the round's first lane {@code i} (rows {@code lo..hi}) after row {@code r}: walked N→S, rows
     * {@code r+1..hi} are left; walked S→N ({@code up}), rows {@code lo..r−1}.
     */
    private int firstCutTarget(int i, int lo, int hi, int r, boolean up) {
        int[][][] cache = up ? firstCutN : firstCut;
        int key = up ? lo : hi;
        int[][] byKey = cache[i];
        if (byKey == null) byKey = cache[i] = new int[rowsOf[i]][];
        int[] a = byKey[key];
        if (a == null) {
            a = byKey[key] = new int[rowsOf[i]];
            Arrays.fill(a, -1);
        }
        int s = a[r];
        if (s < 0) {
            int z = rowZ[rowBase[i] + r];
            s = a[r] = up ? state(i, lo, r - 1, centre[i], z - 1) : state(i, r + 1, hi, centre[i], z + 1);
        }
        return s;
    }

    /** After the round's first lane {@code i} (rows {@code lo..hi}) is done, walked N→S or S→N ({@code up}). */
    private int firstEndTarget(int i, int lo, int hi, boolean up) {
        int[][] cache = up ? firstEndN : firstEnd;
        int key = up ? lo : hi;
        int[] a = cache[i];
        if (a == null) {
            a = cache[i] = new int[rowsOf[i]];
            Arrays.fill(a, -1);
        }
        int s = a[key];
        if (s < 0) {
            int z = up ? rowZ[rowBase[i] + lo] - 1 : rowZ[rowBase[i] + hi] + 1;
            s = a[key] = state(i + 1, 0, rowsOf[i + 1] - 1, centre[i], z);
        }
        return s;
    }

    /** The cut-target cache of lane {@code j} walked {@code south} or north, by walked row (−1 = not created). */
    private int[] cutArray(int j, boolean south) {
        int[][] byDir = south ? cutSouth : cutNorth;
        int[] a = byDir[j];
        if (a == null) {
            a = byDir[j] = new int[rowsOf[j]];
            Arrays.fill(a, -1);
        }
        return a;
    }

    /** After a cut in a later lane {@code j} walked {@code south} or north, after its {@code w}-th walked row. */
    private int cutTarget(int j, boolean south, int w) {
        int[] a = cutArray(j, south);
        int s = a[w];
        if (s < 0) {
            int rb = rowBase[j];
            int rj = rowsOf[j];
            if (south) {
                s = state(j, w + 1, rj - 1, centre[j], rowZ[rb + w] + 1);
            } else {
                int r = rj - 1 - w;
                s = state(j, 0, r - 1, centre[j], rowZ[rb + r] - 1);
            }
            a[w] = s;
        }
        return s;
    }

    /** After a later lane {@code j} walked {@code south} or north is done. */
    private int laneEndTarget(int j, boolean south) {
        if (j == lanes - 1) return TERMINAL;
        int[] a = south ? endSouth : endNorth;
        int s = a[j];
        if (s < 0) {
            int rb = rowBase[j];
            int z = south ? rowZ[rb + rowsOf[j] - 1] + 1 : rowZ[rb] - 1;
            s = a[j] = state(j + 1, 0, rowsOf[j + 1] - 1, centre[j], z);
        }
        return s;
    }

    /** The state {@code (lane, lo, hi, x, z)}, created unreached when new. */
    private int state(int lane, int lo, int hi, int x, int z) {
        int g = group(lane, lo, hi);
        for (int s = gHead[g]; s >= 0; s = sNext[s]) if (sX[s] == x && sZ[s] == z) return s;
        int s = newState(g, x, z);
        sNext[s] = gHead[g];
        gHead[g] = s;
        return s;
    }

    private int newState(int g, int x, int z) {
        if (nStates == sF.length) {
            int n = nStates * 2;
            sGroup = Arrays.copyOf(sGroup, n);
            sX = Arrays.copyOf(sX, n);
            sZ = Arrays.copyOf(sZ, n);
            sBack = Arrays.copyOf(sBack, n);
            sLen = Arrays.copyOf(sLen, n);
            sFirst = Arrays.copyOf(sFirst, n);
            sLast = Arrays.copyOf(sLast, n);
            sNext = Arrays.copyOf(sNext, n);
            sF = Arrays.copyOf(sF, n);
            sUp = Arrays.copyOf(sUp, n);
        }
        int s = nStates++;
        sGroup[s] = g;
        sX[s] = x;
        sZ[s] = z;
        sBack[s] = -1;
        sLen[s] = 0;
        sFirst[s] = 0;
        sLast[s] = 0;
        sNext[s] = -1;
        sF[s] = INF;
        sUp[s] = false;
        return s;
    }

    /** The group of rows {@code lo..hi} of lane {@code lane} (later lanes whole), created and queued when new. */
    private int group(int lane, int lo, int hi) {
        long key = ((long) lane << 42) | ((long) lo << 21) | hi;
        int mask = mapKeys.length - 1;
        int h = (int) (mix(key) & mask);
        while (mapVals[h] >= 0) {
            if (mapKeys[h] == key) return mapVals[h];
            h = (h + 1) & mask;
        }
        if (nGroups == gLane.length) {
            int n = nGroups * 2;
            gLane = Arrays.copyOf(gLane, n);
            gLo = Arrays.copyOf(gLo, n);
            gHi = Arrays.copyOf(gHi, n);
            gHead = Arrays.copyOf(gHead, n);
            gNextBucket = Arrays.copyOf(gNextBucket, n);
        }
        int g = nGroups++;
        gLane[g] = lane;
        gLo[g] = lo;
        gHi[g] = hi;
        gHead[g] = -1;
        int rem = cumCells[lane][hi + 1] - cumCells[lane][lo] + cellsAfter[lane];
        gNextBucket[g] = bucketHead[rem];
        bucketHead[rem] = g;
        mapKeys[h] = key;
        mapVals[h] = g;
        if (++mapSize * 2 > mapKeys.length) rehash();
        return g;
    }

    private void rehash() {
        long[] ok = mapKeys;
        int[] ov = mapVals;
        mapKeys = new long[ok.length * 2];
        mapVals = new int[ok.length * 2];
        Arrays.fill(mapVals, -1);
        int mask = mapKeys.length - 1;
        for (int q = 0; q < ok.length; q++) {
            if (ov[q] < 0) continue;
            int h = (int) (mix(ok[q]) & mask);
            while (mapVals[h] >= 0) h = (h + 1) & mask;
            mapKeys[h] = ok[q];
            mapVals[h] = ov[q];
        }
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return k;
    }

    private static int stackOf(ToIntFunction<String> maxStack, String id) {
        int s = maxStack == null ? DEFAULT_STACK : maxStack.applyAsInt(id);
        return s > 0 ? s : DEFAULT_STACK;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    // =================================================================================================================
    // Describing a round of the path
    // =================================================================================================================

    /** A round of the optimal path being described (mutable, then {@link #toRound()}). */
    private static final class RoundOut {
        int fromLane = -1;
        int fromLo = -1;
        int fromHi = -1;
        int toLane = -1;
        int toLo = -1;
        int toHi = -1;
        int cells;
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> withdraw = new LinkedHashMap<>();
        int slots;
        int capacity;
        int clicks;
        double trip;
        double unwind;
        double chests;
        double clickSeconds;
        double changes;
        double laneRuns;
        int parts;
        double seconds;
        boolean up;

        void round1(SplitDp dp, int a, int t, int cells, int[] n, int used, boolean terminal, int clicks, int w,
                    double trip, double changes, int parts, double v, boolean up) {
            this.up = up;
            this.cells = cells;
            this.slots = used;
            this.capacity = terminal ? dp.cap - dp.reserve : dp.cap;
            this.clicks = clicks;
            for (int k = 0; k < dp.items; k++) {
                if (n[k] > 0) items.put(dp.itemId[k], n[k]);
                int wd = dp.withdraw1(k, n[k]);
                if (wd > 0) withdraw.put(dp.itemId[k], wd);
            }
            this.trip = trip;
            this.unwind = dp.unwind;
            this.chests = dp.chest * w;
            this.clickSeconds = dp.click * clicks;
            this.changes = changes;
            this.laneRuns = dp.laneRun * parts;
            this.parts = parts;
            this.seconds = v;
            if (!terminal) {
                int[] cr = (up ? dp.cellRowF : dp.cellRow)[a];
                if (t == cr.length - 1) {
                    to(dp, a + 1, 0, dp.rowsOf[a + 1] - 1);
                } else if ((up ? dp.ownSouthF : dp.ownSouth)[a]) {
                    to(dp, a, cr[t + 1], dp.rowsOf[a] - 1);
                } else {
                    to(dp, a, 0, cr[t + 1]);
                }
            }
        }

        void later(SplitDp dp, int g, int only, int[] to, int cells, boolean terminal, double changes, int parts,
                   boolean up) {
            this.up = up;
            fromLane = dp.reqIndex[dp.gLane[g]];
            fromLo = dp.gLo[g];
            fromHi = dp.gHi[g];
            if (to != null) to(dp, to[0], to[1], to[2]);
            this.cells = cells;
            this.slots = dp.slots;
            this.capacity = terminal ? dp.cap - dp.reserve : dp.cap;
            this.clicks = dp.slots;
            int[] ks = Arrays.copyOf(dp.touched, dp.nTouched);
            Arrays.sort(ks);
            for (int k : ks) {
                items.put(dp.itemId[k], dp.cnt[k]);
                withdraw.put(dp.itemId[k], dp.cnt[k]);
            }
            this.trip = dp.trip(dp.sX[only], dp.sZ[only], dp.bX, dp.bZ);
            this.unwind = dp.unwind;
            this.chests = dp.chest * dp.wCount;
            this.clickSeconds = dp.click * dp.slots;
            this.changes = changes;
            this.laneRuns = dp.laneRun * parts;
            this.parts = parts;
            this.seconds = trip + unwind + chests + clickSeconds + changes + laneRuns;
        }

        private void to(SplitDp dp, int lane, int lo, int hi) {
            toLane = dp.reqIndex[lane];
            toLo = lo;
            toHi = hi;
        }

        Round toRound() {
            return new Round(fromLane, fromLo, fromHi, toLane, toLo, toHi, cells, Map.copyOf(items), slots, capacity,
                Collections.unmodifiableMap(new LinkedHashMap<>(withdraw)), clicks, trip, unwind, chests, clickSeconds,
                changes, laneRuns, parts, seconds, up);
        }
    }
}
