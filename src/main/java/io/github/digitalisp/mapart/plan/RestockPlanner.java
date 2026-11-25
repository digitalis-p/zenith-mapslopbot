package io.github.digitalisp.mapart.plan;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Decides what to withdraw before the next build run so that as many upcoming lanes as possible
 * can be completed without another trip. Pure. CONTRACT (lead-owned).
 *
 * <p>Two entry points. {@link #plan(List, Map, int, ToIntFunction)} is the original one (lanes, held items, slots).
 * {@link #plan(Request)} adds what a planner over the whole rest of the tile needs: where the bot is, where each item
 * is withdrawn, whether held partial stacks are topped up, and the slots the tile's last load must leave free (map and
 * pane). Its default implementation is the original planner with the last-load room rule the engine used to apply.
 */
public interface RestockPlanner {
    /**
     * @param remaining   lanes still to build, in order
     * @param held        item id → count already in the bot's inventory (materials of the remaining lanes)
     * @param freeSlots   EMPTY inventory slots the load may fill (after reserved slots). The slots the {@code held}
     *                    items occupy come on top: the load's capacity is {@code freeSlots} + those slots.
     * @param maxStack    item id → max stack size
     */
    Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack);

    /**
     * The load for {@link Request}. Default: {@link #plan(List, Map, int, ToIntFunction)}; when that load covers every
     * remaining lane and {@link Request#lastLoadReserve} is above 0, planned again with that many fewer free slots
     * (the map and the pane are fetched after the materials on the tile's last load).
     */
    default Plan plan(Request r) {
        Plan p = plan(r.remaining(), r.held(), r.freeSlots(), r.maxStack());
        if (r.lastLoadReserve() > 0 && p.lanesCovered() >= r.remaining().size()) {
            p = plan(r.remaining(), r.held(), Math.max(0, r.freeSlots() - r.lastLoadReserve()), r.maxStack());
        }
        return p;
    }

    /** True when {@link #plan(Request)} may take long enough (tens of ms) that the engine runs it off the tick thread. */
    default boolean expensive() {
        return false;
    }

    /**
     * Everything a load is planned from.
     *
     * @param remaining       lanes BUILD will run, in order, each with its placements in walking order (the engine's
     *                        replan: the first lane walks N→S, the next S→N, and so on)
     * @param held            item id → count carried now (materials of the remaining lanes)
     * @param heldSlots       item id → hotbar + main slots those items occupy now (a split stack counts each slot);
     *                        an item missing here counts {@code ceil(count / maxStack)}
     * @param freeSlots       empty slots the load may fill (after {@code build.reservedSlots}); capacity = this + the
     *                        slots of {@code held}
     * @param maxStack        item id → max stack size
     * @param lastLoadReserve slots the load that finishes the tile must leave free (empty map and pane not carried)
     * @param topUp           withdrawals top up a held partial stack ({@code WithdrawMode.TOP_UP}): an item with
     *                        {@code n} cells planned and {@code h < n} held ends with {@code roundUp(n, stack)} in
     *                        {@code ceil(n / stack)} slots; false: whole stacks merged into the held ones
     *                        ({@code h + roundUp(n − h, stack)})
     * @param site            where the bot and the items are, for planners that weigh walking and chest time; null =
     *                        packing only
     */
    record Request(List<Lane> remaining, Map<String, Integer> held, Map<String, Integer> heldSlots, int freeSlots,
                   ToIntFunction<String> maxStack, int lastLoadReserve, boolean topUp, @Nullable Site site) {
        public Request {
            remaining = List.copyOf(remaining);
            held = Map.copyOf(held);
            heldSlots = Map.copyOf(heldSlots);
        }

        /** The original four inputs, no reserve, whole stacks, no site. */
        public static Request of(List<Lane> remaining, Map<String, Integer> held, int freeSlots,
                                 ToIntFunction<String> maxStack) {
            return new Request(remaining, held, Map.of(), freeSlots, maxStack, 0, false, null);
        }
    }

    /**
     * Where things are, in world block coordinates (only x and z are used: walking is measured on the horizontal).
     *
     * @param bot       the bot's feet now (the trip for this load starts here)
     * @param depot     item id → where that item is withdrawn (the first chest the stock index lists for it); null =
     *                  not known (no walk counted for it)
     * @param costs     the time model
     * @param flipLanes the planner may run a load's lanes the other way ({@code build.restockLaneFlip},
     *                  {@link LaneFlip}): its first lane S→N, the next N→S, and so on ({@link Plan#flipped}); false =
     *                  every load's first lane N→S, as the engine's replan gives them
     */
    record Site(Pos bot, Function<String, @Nullable Pos> depot, Costs costs, boolean flipLanes) {
        /** No direction choice: every load's first lane N→S. */
        public Site(Pos bot, Function<String, @Nullable Pos> depot, Costs costs) {
            this(bot, depot, costs, false);
        }

        /** This site with the direction choice on or off. */
        public Site flipping(boolean on) {
            return on == flipLanes ? this : new Site(bot, depot, costs, on);
        }
    }

    /**
     * The restock time model (M = measured locally at 1x, M2b = checked on 2b2t).
     *
     * @param walkSpeed      blocks per second on a Baritone leg (M: t = 0.25 s + d / 5.77, 2b2t legs within 1–7 %)
     * @param legSeconds     fixed cost per Baritone leg (M: 0.25 s)
     * @param unwindSeconds  the yaw-unwind gap before every Baritone walk that follows a lane (M: 2.2 s mean)
     * @param chestSeconds   per container opened: look, open, close, next task (M: 1.0 s; M2b −9 %)
     * @param clickSeconds   per container click (M: 6 ticks, ZenithProxy's InventoryManager cadence)
     * @param laneRunSeconds per lane run: end-of-lane settle and turn (E: 30 ticks)
     */
    record Costs(double walkSpeed, double legSeconds, double unwindSeconds, double chestSeconds, double clickSeconds,
                 double laneRunSeconds) {
        public static final Costs DEFAULT = new Costs(5.77, 0.25, 2.2, 1.0, 0.3, 1.5);
    }

    /**
     * @param withdraw     item id → additional count to take from storage
     * @param lanesCovered number of leading lanes fully covered by held + withdraw
     * @param partialCells leading placements of the next lane ({@code remaining.get(lanesCovered)}) also covered;
     *                     0 = none. BUILD runs that lane too, and returns to RESTOCK when its items run out.
     * @param stopAtCut    BUILD runs exactly the covered lanes and the first {@code partialCells} placements of the
     *                     next (the lane ends after them) and nothing beyond, instead of walking the partial lane to
     *                     its end and running further lanes whose items happen to be carried
     * @param rounds       loads the planner expects for the rest of the tile, this one included (0 = not estimated)
     * @param seconds      the planner's restock-time estimate for the rest of the tile (walking, chests, lane changes;
     *                     0 = not estimated)
     * @param flipped      the load walks its lanes the other way ({@link Site#flipLanes}, {@link LaneFlip#walked}):
     *                     {@code partialCells} are the leading cells of the flipped lane, and BUILD runs the flipped
     *                     lanes ({@link RestockLoads#cutLanes})
     */
    record Plan(Map<String, Integer> withdraw, int lanesCovered, int partialCells, boolean stopAtCut, int rounds,
                double seconds, boolean flipped) {
        public Plan(Map<String, Integer> withdraw, int lanesCovered, int partialCells, boolean stopAtCut, int rounds,
                    double seconds) {
            this(withdraw, lanesCovered, partialCells, stopAtCut, rounds, seconds, false);
        }

        public Plan(Map<String, Integer> withdraw, int lanesCovered, int partialCells) {
            this(withdraw, lanesCovered, partialCells, false, 0, 0.0);
        }

        public Plan(Map<String, Integer> withdraw, int lanesCovered) {
            this(withdraw, lanesCovered, 0);
        }

        /** Lanes BUILD runs with this load: the covered ones and a partially loaded next one. */
        public int lanesLoaded() {
            return lanesCovered + (partialCells > 0 ? 1 : 0);
        }
    }
}
