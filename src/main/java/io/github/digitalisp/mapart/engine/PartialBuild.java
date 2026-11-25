package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Partial build ({@code build.partialBuild}): a tile short of some materials is
 * built as far as the stock allows. Pure rules: what STOCK_CHECK does with a shortfall, and which cells the restock
 * loads, BUILD and VERIFY leave out.
 *
 * <p>The cells left out are, per short item, its last {@code shortfall} cells in build order. The shortfall (remaining
 * need minus inventory minus stock) stays the same while the tile is built, since every placed block lowers the need
 * and the inventory or stock alike, so the same cells (the last ones of the build) are left out every round until stock
 * arrives.
 *
 * <p>No trickling (routing study, ellen map 1: building with a shortage, 26 of 28 loads carried 1 to 10 slots, 32 trips
 * where full stock needs 9): the loads are planned only for the cells the stock covers, so they are as full as with
 * full stock, and a started tile waiting for materials goes on only when a trip is worth it ({@link #worthATrip}): the
 * stock that came covers one full load ({@link #loadCells}), {@code build.partialTripMinCells} cells (one stack by
 * default), or all the cells left of some item. A few blocks more of an item that is nearly out wait for the rest of it.
 * What the bot carries needs no trip: those cells are built at once ({@link #carried}, {@code build.partialBuildCarried}).
 */
final class PartialBuild {
    private PartialBuild() { }

    /** What STOCK_CHECK does with the tile. */
    enum Decision {
        /** Nothing short: build as usual. */
        BUILD,
        /** Short of some items: build every cell the stock covers, leave the rest out. */
        PARTIAL,
        /** Every cell left is short and the platform holds this tile already: wait on it for the materials. */
        WAIT,
        /** Hold the whole job as WAITING_MATERIALS (other jobs first), as before partial builds. */
        HOLD
    }

    /**
     * @param enabled    {@code build.partialBuild}
     * @param minShare   {@code build.partialBuildMinShare}: for a tile with nothing placed, the share of its remaining
     *                   cells the stock must cover to start a partial build
     * @param remaining  cells the tile still needs (missing or wrong)
     * @param shortCells of them, those neither the inventory nor the stock covers
     * @param started    the platform holds placed blocks of this tile
     * @param worthATrip the coverable cells are worth a trip ({@link #worthATrip}); a started tile otherwise waits
     */
    static Decision decide(boolean enabled, double minShare, int remaining, int shortCells, boolean started,
                           boolean worthATrip) {
        if (shortCells <= 0) return Decision.BUILD;
        if (!enabled) return Decision.HOLD;
        int coverable = remaining - shortCells;
        if (coverable <= 0) return started ? Decision.WAIT : Decision.HOLD;
        if (started) return worthATrip ? Decision.PARTIAL : Decision.WAIT;
        return coverable >= Math.max(0.0, minShare) * remaining ? Decision.PARTIAL : Decision.HOLD;
    }

    /** Material slots of a full load (36 inventory slots less the reserved ones). */
    static final int INVENTORY_SLOTS = 36;

    /** Cells one full load covers: every material slot a stack of 64 ({@code build.reservedSlots} kept free). */
    static int loadCells(int reservedSlots) {
        return Math.max(1, INVENTORY_SLOTS - Math.max(0, reservedSlots)) * 64;
    }

    /**
     * A started tile short of some items builds on only when that is worth a trip: the coverable cells fill at least
     * {@code loadCells}, or at least {@code minCells} ({@code build.partialTripMinCells}; 0 = no such minimum), or some
     * item still needed is not short at all (a round places all of it; in the wait every item left is a short one, so
     * this means one of them came in full). Otherwise the few cells wait for the rest. With carried items built apart
     * ({@code build.partialBuildCarried}) {@code needs} is what the inventory does not cover: the trip is judged on what
     * the stock adds.
     */
    static boolean worthATrip(Map<String, Integer> needs, Map<String, Integer> shortfall, int loadCells, int minCells) {
        int coverable = cells(needs) - cells(shortfall);
        if (coverable >= loadCells) return true;
        if (minCells > 0 && coverable >= minCells) return true;
        for (Map.Entry<String, Integer> e : needs.entrySet())
            if (e.getValue() > 0 && shortfall.getOrDefault(e.getKey(), 0) <= 0) return true;
        return false;
    }

    /**
     * The cells a trip must bring before it is worth making ({@link #worthATrip}, besides all of an item): one full load,
     * or {@code minCells} when that is set and smaller.
     */
    static int tripCells(int loadCells, int minCells) {
        return minCells > 0 ? Math.min(loadCells, minCells) : loadCells;
    }

    /**
     * Per item of {@code needs}, the cells the inventory covers ({@code have}: item → count carried); items with none
     * carried left out. A started tile builds these at once, with no trip ({@code build.partialBuildCarried}).
     */
    static Map<String, Integer> carried(Map<String, Integer> needs, Map<String, Integer> have) {
        Map<String, Integer> out = new TreeMap<>();
        needs.forEach((id, n) -> {
            int c = Math.min(Math.max(0, n), Math.max(0, have.getOrDefault(id, 0)));
            if (c > 0) out.put(id, c);
        });
        return out;
    }

    /**
     * {@code lanes} without, per item of {@code shortfall}, its last {@code shortfall} placements in lane order; lanes
     * left without placements are dropped, untouched lanes are returned as they are.
     */
    static List<Lane> withoutShort(List<Lane> lanes, Map<String, Integer> shortfall) {
        if (shortfall == null || shortfall.isEmpty() || lanes == null) return lanes;
        Map<String, Integer> count = new HashMap<>();
        for (Lane l : lanes) {
            for (Placement p : l.placements()) if (shortfall.containsKey(p.itemId())) count.merge(p.itemId(), 1, Integer::sum);
        }
        if (count.isEmpty()) return lanes;
        Map<String, Integer> keep = new HashMap<>();
        count.forEach((id, n) -> keep.put(id, Math.max(0, n - shortfall.get(id))));
        List<Lane> out = new ArrayList<>(lanes.size());
        for (Lane l : lanes) {
            List<Placement> ps = new ArrayList<>(l.placements().size());
            for (Placement p : l.placements()) {
                Integer left = keep.get(p.itemId());
                if (left == null) {
                    ps.add(p);
                } else if (left > 0) {
                    keep.put(p.itemId(), left - 1);
                    ps.add(p);
                }
            }
            if (ps.isEmpty()) continue;
            out.add(ps.size() == l.placements().size() ? l : new Lane(l.index(), l.from(), l.to(), List.copyOf(ps)));
        }
        return out;
    }

    /** {@code need} less {@code shortfall}, per item (items at 0 dropped). */
    static Map<String, Integer> minus(Map<String, Integer> need, Map<String, Integer> shortfall) {
        if (shortfall.isEmpty()) return need;
        Map<String, Integer> out = new TreeMap<>();
        need.forEach((id, n) -> {
            int left = n - shortfall.getOrDefault(id, 0);
            if (left > 0) out.put(id, left);
        });
        return out;
    }

    /** Cells of {@code needs} that {@code avail} does not cover. */
    static int missing(Map<String, Integer> needs, Map<String, Integer> avail) {
        int n = 0;
        for (Map.Entry<String, Integer> e : needs.entrySet()) n += Math.max(0, e.getValue() - avail.getOrDefault(e.getKey(), 0));
        return n;
    }

    static int cells(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += Math.max(0, v);
        return n;
    }

    /** "1,122 cyan terracotta, 1 crimson nylium": most missing first ({@link ShortageText#amounts}). */
    static String items(Map<String, Integer> shortfall) {
        return ShortageText.amounts(shortfall);
    }
}
