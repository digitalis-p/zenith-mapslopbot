package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * What the engine does with a {@link RestockPlanner.Plan}, kept pure so the engine and the restock model in the tests
 * share it: the lanes BUILD runs for a cut load, and the check for carried materials that crowd the load.
 */
public final class RestockLoads {
    /** Fewest cells more a load must cover before carried materials it does not use are returned to make room. */
    public static final int RETURN_MIN_CELLS = 64;
    /**
     * Cells each item returned within the lane the load ends in ({@link #lanePick}) must add: it is withdrawn again for
     * a later load (a container visit). One row of a 9-wide lane.
     */
    static final int RETURN_ITEM_CELLS = 9;
    /** Planner runs {@code keepReused} may add to find the carried items the freed load needs again. */
    static final int MAX_REUSE_ROUNDS = 3;

    private RestockLoads() { }

    /**
     * The load as carried ({@code kept}) and, when carried materials that load does not use hold slots, the load with
     * them gone and their slots free ({@code freed}, null otherwise).
     */
    public record Choice(RestockPlanner.Plan kept, RestockPlanner.Plan freed, Map<String, Integer> unused,
                         int unusedSlots, int keptCells, int freedCells) {
        /** Returning the unused materials lets the load cover at least {@link #RETURN_MIN_CELLS} cells more. */
        public boolean clogged() {
            return freed != null && (freedCells >= keptCells + RETURN_MIN_CELLS || (keptCells == 0 && freedCells > 0));
        }
    }

    /**
     * Plans the load twice when needed (pure; the engine runs it on the planner thread for an expensive planner): as
     * carried, and, if materials carried for later lanes hold slots this load does not use, without them. Live j0002
     * (mixed art, many materials): partial stacks needed only by later lanes filled the inventory, every load covered a
     * few dozen cells and the tile took 29 restocks.
     */
    public static Choice choose(RestockPlanner planner, RestockPlanner.Request req) {
        return choose(planner, req, id -> true);
    }

    /** {@link #choose(RestockPlanner, RestockPlanner.Request)} where only {@code returnable} items may go back. */
    public static Choice choose(RestockPlanner planner, RestockPlanner.Request req, Predicate<String> returnable) {
        return choose(planner, req, returnable, false);
    }

    /**
     * As {@link #choose(RestockPlanner, RestockPlanner.Request, Predicate)}; with {@code keepReused} a carried material
     * the load as carried does not use but the freed load does (it reaches lanes that need it) stays: returning it
     * would only have the same restock withdraw it again, a whole stack and a chest visit for each (live, 13 maps of
     * restocks of 8 maps: 9 of 52 drops took items the load withdrew again right after, up to 9 of 10 stacks). The freed load is
     * planned again with those kept until none is reused, so the load that is chosen needs no more than the slots it
     * has.
     */
    public static Choice choose(RestockPlanner planner, RestockPlanner.Request req, Predicate<String> returnable,
                                boolean keepReused) {
        return choose(planner, req, returnable, keepReused, false);
    }

    /**
     * As {@link #choose(RestockPlanner, RestockPlanner.Request, Predicate, boolean)}; with {@code laneCells} (and
     * {@code keepReused}) the return also works within the lane the load ends in: the carried materials the load does
     * not use go back furthest first use first ({@link #furthestFirst}, Belady), as many as let it cover the most
     * cells without reaching any of them ({@link #lanePick}), when that covers more than the list above. Live
     * 2026-10-31 (map "haruhi", many items in short runs of rows): the inventory held partial stacks the lane in
     * progress used further on; the load with all of them gone reached the whole lane, so every one counted as needed
     * again, nothing went back, and each load got the one free slot: 9 to 108 cells a trip.
     */
    public static Choice choose(RestockPlanner planner, RestockPlanner.Request req, Predicate<String> returnable,
                                boolean keepReused, boolean laneCells) {
        Choice old = chooseUnused(planner, req, returnable, keepReused);
        if (!keepReused || !laneCells) return old;
        RestockPlanner.Plan kept = old.kept();
        Set<String> used = new HashSet<>();
        cells(req, kept, used);
        List<String> order = new ArrayList<>();
        for (var e : req.held().entrySet()) {
            if (!used.contains(e.getKey()) && e.getValue() > 0 && returnable.test(e.getKey())) order.add(e.getKey());
        }
        if (order.isEmpty()) return old;
        order = furthestFirst(LaneFlip.walked(req.remaining(), kept), order);
        // the cheap fill finds the list (one plan per length); the planner plans the load for it
        RestockPlanner probe = planner.expensive() ? new GreedyRestockPlanner() : planner;
        Set<String> picked = lanePick(probe, req, order);
        if (picked.isEmpty() || picked.equals(old.unused().keySet())) return old;
        Choice lane = freedChoice(planner, req, kept, old.keptCells(), subMap(req.held(), picked), true);
        int oldCells = old.freed() == null ? old.keptCells() : old.freedCells();
        if (lane.freed() == null || lane.freedCells() <= oldCells) return old;
        // never an item returned for this load and withdrawn again for it (the planner's rounds may run out first)
        Set<String> uses = new HashSet<>();
        cells(req, lane.freed(), uses);
        return Collections.disjoint(uses, lane.unused().keySet()) ? lane : old;
    }

    /**
     * {@code items} in the order they go back from a load over {@code walked} (the lanes as the load walks them): the
     * items no cell uses first, then by their first cell, the furthest first (ties by id).
     */
    public static List<String> furthestFirst(List<Lane> walked, Collection<String> items) {
        Set<String> want = new HashSet<>(items);
        Map<String, Integer> first = new HashMap<>();
        int i = 0;
        for (Lane l : walked) {
            for (Placement p : l.placements()) {
                if (p.itemId() != null && want.contains(p.itemId())) first.putIfAbsent(p.itemId(), i);
                i++;
            }
        }
        List<String> out = new ArrayList<>(new TreeSet<>(items));
        out.sort(Comparator.comparingInt((String id) -> first.getOrDefault(id, Integer.MAX_VALUE)).reversed());
        return out;
    }

    /**
     * The leading items of {@code order} ({@link #furthestFirst}) whose return lets {@code probe}'s load cover the most
     * cells while it uses none of them, each item returned counted at {@link #RETURN_ITEM_CELLS} cells (it is withdrawn
     * again for a later load); empty when no length gains. Lengths are tried from one up, and the first load that
     * reaches a returned item ends the search: a longer list frees more slots and returns an item needed sooner.
     */
    static Set<String> lanePick(RestockPlanner probe, RestockPlanner.Request req, List<String> order) {
        int base = cells(req, probe.plan(req), new HashSet<>());
        int bestScore = base;
        int bestLen = 0;
        for (int k = 1; k <= order.size(); k++) {
            Map<String, Integer> gone = subMap(req.held(), order.subList(0, k));
            Set<String> uses = new HashSet<>();
            int n = cells(req, probe.plan(without(req, gone)), uses);
            if (!Collections.disjoint(uses, gone.keySet())) break;
            int score = n - RETURN_ITEM_CELLS * k;
            if (n > base && score > bestScore) {
                bestScore = score;
                bestLen = k;
            }
        }
        return new TreeSet<>(order.subList(0, bestLen));
    }

    private static Map<String, Integer> subMap(Map<String, Integer> m, Collection<String> keys) {
        Map<String, Integer> out = new TreeMap<>();
        for (String k : keys) if (m.containsKey(k)) out.put(k, m.get(k));
        return out;
    }

    /** {@code req} with the items of {@code gone} returned: not held, the slots they hold free. */
    static RestockPlanner.Request without(RestockPlanner.Request req, Map<String, Integer> gone) {
        Map<String, Integer> held = new TreeMap<>(req.held());
        Map<String, Integer> heldSlots = new TreeMap<>(req.heldSlots());
        held.keySet().removeAll(gone.keySet());
        heldSlots.keySet().removeAll(gone.keySet());
        return new RestockPlanner.Request(req.remaining(), held, heldSlots, req.freeSlots() + slotsOf(req, gone),
            req.maxStack(), req.lastLoadReserve(), req.topUp(), req.site());
    }

    /** Slots the items of {@code gone} hold ({@link RestockPlanner.Request#heldSlots}, else whole stacks). */
    private static int slotsOf(RestockPlanner.Request req, Map<String, Integer> gone) {
        int slots = 0;
        for (var e : gone.entrySet()) {
            int s = Math.max(1, req.maxStack().applyAsInt(e.getKey()));
            Integer u = req.heldSlots().get(e.getKey());
            slots += u != null && u > 0 ? u : (e.getValue() + s - 1) / s;
        }
        return slots;
    }

    /** The list of the materials the load as carried does not use (the overloads above without {@code laneCells}). */
    private static Choice chooseUnused(RestockPlanner planner, RestockPlanner.Request req, Predicate<String> returnable,
                                       boolean keepReused) {
        RestockPlanner.Plan kept = planner.plan(req);
        Set<String> used = new HashSet<>();
        int keptCells = cells(req, kept, used);
        Map<String, Integer> unused = new TreeMap<>();
        for (var e : req.held().entrySet()) {
            if (used.contains(e.getKey()) || e.getValue() <= 0 || !returnable.test(e.getKey())) continue;
            unused.put(e.getKey(), e.getValue());
        }
        if (unused.isEmpty()) return new Choice(kept, null, Map.of(), 0, keptCells, keptCells);
        return freedChoice(planner, req, kept, keptCells, unused, keepReused);
    }

    /**
     * The load {@code kept} (covering {@code keptCells}) against the load with the items of {@code unused} returned;
     * with {@code keepReused} those the freed load reaches for come off the list (changed here), and the load is
     * planned again without the rest.
     */
    private static Choice freedChoice(RestockPlanner planner, RestockPlanner.Request req, RestockPlanner.Plan kept,
                                      int keptCells, Map<String, Integer> unused, boolean keepReused) {
        for (int round = 0; ; round++) {
            int slots = slotsOf(req, unused);
            RestockPlanner.Plan freed = planner.plan(without(req, unused));
            Set<String> freedUses = new HashSet<>();
            int freedCells = cells(req, freed, freedUses);
            boolean reused = keepReused && round < MAX_REUSE_ROUNDS && unused.keySet().removeAll(freedUses);
            if (!reused) {
                // rounds ran out with the list still changing: this plan may reach for an item that goes; it is
                // the best the list as it stands can do
                if (unused.isEmpty()) return new Choice(kept, null, Map.of(), 0, keptCells, keptCells);
                return new Choice(kept, freed, Map.copyOf(unused), slots, keptCells, freedCells);
            }
            if (unused.isEmpty()) return new Choice(kept, null, Map.of(), 0, keptCells, keptCells);
        }
    }

    /**
     * Seconds of restock the rest of the tile saves when this load covers the cells of {@code dropped} instead of those
     * of {@code carried} (the same request with leftovers gone / kept), by the planner's own estimate of the loads
     * left: the cells gained are that share of a load ({@code remaining cells / rounds}), a load costs
     * {@code seconds / rounds}. Negative when the plan has no estimate ({@link RestockPlanner.Plan#rounds()} 0).
     */
    public static double gainSeconds(List<Lane> remaining, RestockPlanner.Plan dropped, RestockPlanner.Plan carried) {
        if (dropped.rounds() <= 0) return -1;
        double cellsLeft = 0;
        for (Lane l : remaining) cellsLeft += l.placements().size();
        double perLoad = Math.max(1, cellsLeft / dropped.rounds());
        double gained = cellsLoaded(remaining, dropped) - cellsLoaded(remaining, carried);
        return Math.max(0, gained) / perLoad * (dropped.seconds() / dropped.rounds());
    }

    /** Cells of {@code lanes} the load of {@code p} covers: the whole lanes and the partial one. */
    public static int cellsLoaded(List<Lane> lanes, RestockPlanner.Plan p) {
        int n = p.partialCells();
        for (int i = 0; i < Math.min(p.lanesCovered(), lanes.size()); i++) n += lanes.get(i).placements().size();
        return n;
    }

    /** Cells of the load {@code p} covers; the items they need are added to {@code items}. */
    private static int cells(RestockPlanner.Request req, RestockPlanner.Plan p, Set<String> items) {
        int n = 0;
        for (Lane l : cutLanes(req.remaining(), p)) {
            n += l.placements().size();
            for (Placement x : l.placements()) if (x.itemId() != null) items.add(x.itemId());
        }
        return n;
    }

    /**
     * {@code lanes} cut to the plan: the covered lanes, then the first {@code partialCells} cells of the next one; all
     * of them walked the other way when the plan is {@link RestockPlanner.Plan#flipped} ({@link LaneFlip}).
     */
    public static List<Lane> cutLanes(List<Lane> lanes, RestockPlanner.Plan rp) {
        lanes = LaneFlip.walked(lanes, rp);
        int covered = Math.max(0, Math.min(rp.lanesCovered(), lanes.size()));
        List<Lane> out = new ArrayList<>(lanes.subList(0, covered));
        if (covered < lanes.size() && rp.partialCells() > 0) {
            Lane next = lanes.get(covered);
            int n = Math.min(rp.partialCells(), next.placements().size());
            out.add(n >= next.placements().size() ? next : cut(next, n));
        }
        return out;
    }

    /**
     * The first {@code n} placements of {@code lane}; the walk ends one row past the last of them (as the serpentine
     * planner ends a lane one row past its last row), never past the lane's own end.
     */
    public static Lane cut(Lane lane, int n) {
        List<Placement> ps = List.copyOf(lane.placements().subList(0, n));
        Pos from = lane.from();
        Pos to = lane.to();
        int step = Integer.signum(to.z() - from.z());
        if (step == 0 || ps.isEmpty()) return new Lane(lane.index(), from, to, ps);
        int endZ = ps.getLast().pos().z() + step;
        endZ = step > 0 ? Math.min(endZ, to.z()) : Math.max(endZ, to.z());
        return new Lane(lane.index(), from, new Pos(to.x(), to.y(), endZ), ps);
    }
}
