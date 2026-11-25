package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/**
 * Split DP restock planner: fewest refills is not
 * fastest, so the load is chosen by the cheapest way to finish the whole rest of the tile.
 *
 * <p>A forward DP over the rest of the tile chooses where every load ends: after a row, at a lane end, or (first load
 * only) at the last cell that still fits or that the carried items cover. A first load that withdraws nothing ends
 * only at a lane end or where the carried items run out. It follows the engine's replan order (each load starts with the remaining
 * lane N→S, the next lanes alternate S→N, N→S), counts slots as the bot will hold them (held items and their slots,
 * top-up or whole stacks, the last load's map-and-pane reserve) and prices every load with {@link Costs}: the walk past
 * the withdrawn items' depots, one chest per item, clicks, lane changes and lane runs. The returned plan is the first
 * load of the cheapest path, with {@link Plan#stopAtCut} set: BUILD stops exactly where the DP cut. {@link SplitDp}
 * holds the model and the search.
 *
 * <p>{@code lanesFirst} is the roadmap's two-lane rule with the split DP as its fallback (rank 1 and item 15): while a
 * load can finish the current lane and the next one, loads end at lane ends; when that lane pair does not fit, lanes
 * are cut wherever it is cheapest.
 */
public final class SplitRestockPlanner implements RestockPlanner {
    private final boolean lanesFirst;
    private final BooleanSupplier carriedGuard;

    /** @param lanesFirst the two-lane rule: loads end at lane ends while a lane pair fits, else they cut lanes */
    public SplitRestockPlanner(boolean lanesFirst) {
        this(lanesFirst, () -> false);
    }

    /**
     * @param lanesFirst   the two-lane rule
     * @param carriedGuard read on every plan: a round 1 cut short on a round 2 the carried stacks leave no room for
     *                     gives way to the max fill ({@code build.restockCarriedGuard}, {@link SplitDp#launders})
     */
    public SplitRestockPlanner(boolean lanesFirst, BooleanSupplier carriedGuard) {
        this.lanesFirst = lanesFirst;
        this.carriedGuard = carriedGuard;
    }

    public boolean lanesFirst() {
        return lanesFirst;
    }

    @Override
    public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
        return plan(Request.of(remaining, held, freeSlots, maxStack));
    }

    @Override
    public Plan plan(Request r) {
        return solve(r).plan();
    }

    /** The whole optimal path for {@code r}, for tests and diagnostics. */
    Result solve(Request r) {
        return SplitDp.solve(r, lanesFirst, carriedGuard.getAsBoolean());
    }

    @Override
    public boolean expensive() {
        return true;
    }

    /**
     * The DP's answer.
     *
     * @param plan     the first load (what {@link #plan(Request)} returns)
     * @param rounds   the loads of the optimal path in order; with {@code complete} false only the fallback load
     * @param complete a path to the end of the tile exists; false: no complete path (a row or the last load never
     *                 fits), and the plan is the longest allowed first load with {@code rounds} and {@code seconds} 0
     * @param stats    search size and the cut steps the work guard chose
     */
    record Result(Plan plan, List<Round> rounds, boolean complete, Stats stats) {
        /** Total seconds of the path (the plan's estimate). */
        double seconds() {
            return plan.seconds();
        }
    }

    /**
     * One load of the path.
     *
     * @param fromLane   request lane of the state the load starts from (−1: the first load, from the bot)
     * @param fromLo     first remaining row of that lane (rows are indexed by z ascending), −1 for the first load
     * @param fromHi     last remaining row of that lane, −1 for the first load
     * @param toLane     request lane of the state after the load; −1 when the load finishes the tile
     * @param toLo       first remaining row of that lane after the load
     * @param toHi       last remaining row of that lane after the load
     * @param cells      placements the load covers (first load: exact; mid-row cut included)
     * @param items      item → placements in the load
     * @param slots      inventory slots the load takes (first load: with every held item)
     * @param capacity   slots it may take ({@code freeSlots} + held slots, minus the reserve on the last load)
     * @param withdraw   item → count taken from storage (first load: the plan's withdrawal; later: the cells)
     * @param clicks     container clicks
     * @param tripSeconds       legs and walking from the start position past the depots to the load's first row
     * @param unwindSeconds     the trip's unwind gap
     * @param chestSeconds      one container per withdrawn item
     * @param clickSeconds      the clicks
     * @param laneChangeSeconds unwind and walk between the load's lane parts
     * @param laneRunSeconds    one lane run per lane part
     * @param laneParts         lanes (or lane parts) the load runs
     * @param seconds           the load's total
     * @param flipped           the load walks its first lane S→N, the next N→S, … ({@link RestockPlanner.Site#flipLanes})
     */
    record Round(int fromLane, int fromLo, int fromHi, int toLane, int toLo, int toHi, int cells,
                 Map<String, Integer> items, int slots, int capacity, Map<String, Integer> withdraw, int clicks,
                 double tripSeconds, double unwindSeconds, double chestSeconds, double clickSeconds,
                 double laneChangeSeconds, double laneRunSeconds, int laneParts, double seconds, boolean flipped) {
        boolean terminal() {
            return toLane < 0;
        }
    }

    /**
     * Search size.
     *
     * @param states       states created (all positions)
     * @param groups       groups created (states with the same remaining rows)
     * @param rowSteps     rows added to scanned rounds in the final run
     * @param rowStep      cuts at row boundaries {@code b % rowStep == 0} only (1 = every row)
     * @param intervalStep cuts that leave a middle part of a lane at {@code b % intervalStep == 0} only (1 = every row)
     * @param attempts     runs (more than 1 when a run overshot the hard work limit and was repeated coarser)
     */
    record Stats(int states, int groups, long rowSteps, int rowStep, int intervalStep, int attempts) {
        /** Every row end was a candidate: the result is exact over the model. */
        boolean exact() {
            return rowStep == 1 && intervalStep == 1;
        }
    }
}
