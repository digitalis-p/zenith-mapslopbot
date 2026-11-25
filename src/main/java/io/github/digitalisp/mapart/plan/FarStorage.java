package io.github.digitalisp.mapart.plan;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Far storage ({@code build.farStorage}): material containers far from the
 * platform being built (another platform's sorter) cost minutes per trip, so a restock load that goes there takes
 * along the far items of the upcoming lanes, and none goes there for a few blocks. Pure; the engine supplies the stock
 * split ({@link Stock}) from its stock index.
 *
 * <p><b>Far</b>: a material container more than {@code build.farStorageBlocks} blocks (horizontally) outside the
 * platform's 128×128 map area ({@link #far}). On the owner's base each platform's own sorter is 5-9 blocks off its area, the
 * other platform's sorter 129 blocks or more. Near containers are emptied before far ones. Far containers are grouped
 * by the configured material region they lie in ({@code site.storage.materialRegions}).
 *
 * <p><b>The rules</b> (live 2026-10-13, ellen on the soul sand platform, most of the stock in the moss sorter about 250
 * blocks away: 07:13 a trip of about 4 minutes for 59 gray + 7 light blue terracotta; 07:47 a load wanted 64 purple
 * terracotta, the near chest had 51, and the sweep walked to the moss sorter for about 12):
 * <ol>
 *   <li><b>Batching</b> ({@link Batching}, {@code build.farStorageBatching}): a load that has to go to far storage
 *       also takes, from the far region it visits, the cells of the items only far storage supplies for as many
 *       upcoming lanes as keep the loads until the next far trip healthy, so later loads need no far trip for
 *       them.</li>
 *   <li><b>No far trip for a few blocks</b> ({@link #fewFarCells}): when the cells of a load that only far storage can
 *       supply are fewer than {@code build.farTripMinCells} and a later load of the tile goes there anyway, those
 *       items' far cells are left out of the load and its BUILD round and go with that later load. When no later load
 *       goes there, this load takes them now.</li>
 *   <li><b>The stack round-up stays near</b> ({@link #farCap}): a top-up withdrawal asks for whole stacks; the part of
 *       a withdrawal beyond the cells the load serves never comes from far storage (07:13:25: the 7 light blue
 *       terracotta from the moss sorter filled a stack the near chest had covered all but 7 of).</li>
 * </ol>
 */
public final class FarStorage {
    private FarStorage() { }

    /** Horizontal distance from (x, z) to the nearest cell of the 128×128 map area of {@code geo}; 0 inside it. */
    public static double distanceToArea(PlatformGeometry geo, int x, int z) {
        int dx = Math.max(0, Math.max(geo.minX() - x, x - (geo.minX() + TileArt.SIZE - 1)));
        int dz = Math.max(0, Math.max(geo.minZ() - z, z - (geo.minZ() + TileArt.SIZE - 1)));
        return Math.hypot(dx, dz);
    }

    /** As {@link #far(PlatformGeometry, int, Pos)} for the map area whose north-west corner is ({@code minX}, {@code minZ}). */
    public static boolean far(int minX, int minZ, int blocks, Pos chest) {
        if (blocks <= 0) return false;
        int dx = Math.max(0, Math.max(minX - chest.x(), chest.x() - (minX + TileArt.SIZE - 1)));
        int dz = Math.max(0, Math.max(minZ - chest.z(), chest.z() - (minZ + TileArt.SIZE - 1)));
        return Math.hypot(dx, dz) > blocks;
    }

    /** {@code chest} is far storage for the platform of {@code geo}: more than {@code blocks} outside its map area. */
    public static boolean far(@Nullable PlatformGeometry geo, int blocks, Pos chest) {
        return geo != null && blocks > 0 && distanceToArea(geo, chest.x(), chest.z()) > blocks;
    }

    /**
     * Where the stock is, by the stock index.
     *
     * @param near item id → count in the platform's near (own) storage
     * @param far  item id → far region → count, regions in the order a withdrawal reaches them (nearest container to
     *             the platform first); a region is an index into {@code site.storage.materialRegions} (−1: in none)
     */
    public record Stock(Map<String, Integer> near, Map<String, Map<Integer, Integer>> far) {
        public static final Stock NONE = new Stock(Map.of(), Map.of());

        public Stock {
            near = Map.copyOf(near);
            Map<String, Map<Integer, Integer>> f = new HashMap<>();
            far.forEach((id, regions) -> {
                Map<Integer, Integer> r = new LinkedHashMap<>();
                regions.forEach((k, n) -> {
                    if (n != null && n > 0) r.put(k, n);
                });
                if (!r.isEmpty()) f.put(id, Collections.unmodifiableMap(r));
            });
            far = Collections.unmodifiableMap(f);
        }

        public int near(String id) {
            return Math.max(0, near.getOrDefault(id, 0));
        }

        /** All of {@code id} in far storage. */
        public int far(String id) {
            int n = 0;
            for (int c : far.getOrDefault(id, Map.of()).values()) n += c;
            return n;
        }

        /** Far regions holding {@code id} in withdrawal order, with their counts. */
        public Map<Integer, Integer> farRegions(String id) {
            return far.getOrDefault(id, Map.of());
        }

        public boolean anyFar() {
            return !far.isEmpty();
        }
    }

    /** Item → cells of {@code lanes}. */
    public static Map<String, Integer> cells(List<Lane> lanes) {
        Map<String, Integer> m = new TreeMap<>();
        for (Lane l : lanes) for (Placement p : l.placements()) if (p.itemId() != null) m.merge(p.itemId(), 1, Integer::sum);
        return m;
    }

    /**
     * Per item, the cells of {@code lanes} only far storage can supply: what they need beyond {@code held} and the near
     * stock, at most what far storage holds. Items without far stock are left out (their shortfall is no far trip).
     */
    public static Map<String, Integer> farPart(List<Lane> lanes, Map<String, Integer> held, Stock stock) {
        return farPart(cells(lanes), held, stock);
    }

    /** {@link #farPart(List, Map, Stock)} of item → cells. */
    public static Map<String, Integer> farPart(Map<String, Integer> cells, Map<String, Integer> held, Stock stock) {
        Map<String, Integer> out = new TreeMap<>();
        if (!stock.anyFar()) return out;
        cells.forEach((id, n) -> {
            int f = Math.min(stock.far(id), n - Math.max(0, held.getOrDefault(id, 0)) - stock.near(id));
            if (f > 0) out.put(id, f);
        });
        return out;
    }

    /**
     * What a far container may give of an item: at most the cells the load serves ({@code serve}) beyond what is
     * carried ({@code held}) and what the containers before it gave ({@code taken}). The rest of a whole-stack request
     * (the top-up round-up) is not needed and never comes from far storage.
     */
    public static int farCap(int serve, int held, int taken) {
        return Math.max(0, serve - Math.max(0, held) - Math.max(0, taken));
    }

    /**
     * Cells left out because no far trip is worth them.
     *
     * @param lanes the lanes without them
     * @param cells item → cells left out of the lanes (each item's last ones in build order)
     * @param load  item → the load's cells only far storage could supply (what made the trip not worth it)
     */
    public record LeftOut(List<Lane> lanes, Map<String, Integer> cells, Map<String, Integer> load) {
        public LeftOut {
            lanes = List.copyOf(lanes);
            cells = Map.copyOf(cells);
            load = Map.copyOf(load);
        }

        /** Cells left out. */
        public int count() {
            return sum(cells);
        }

        /** The load's far cells. */
        public int loadCells() {
            return sum(load);
        }

        private static int sum(Map<String, Integer> m) {
            int n = 0;
            for (int c : m.values()) n += c;
            return n;
        }
    }

    /**
     * No far trip for a few blocks. A planned load serves {@code served} (item → cells: the lanes it covers, a part of
     * {@code lanes}, and a far batch's cells); the part of them neither {@code held} nor the near stock covers comes
     * from far storage ({@link #farPart}). When that is more than 0 and fewer than {@code minCells}, and a later load
     * of the tile goes to far storage anyway (the far cells of {@code lanes} beyond what this load serves are at least
     * {@code minCells}), the far cells of those items in all of {@code lanes} (each item's last ones in build order:
     * near stock is withdrawn first, so the first cells are the near ones) are left out: they go with that load. Null
     * when nothing is left out: the load needs no far cells or enough of them for a trip, no later load goes there
     * anyway (this load takes them now: deferring them would only move the same trip to the tile's end and walk back
     * over their lanes), {@code minCells} is 0, or nothing else would be left to build.
     */
    public static @Nullable LeftOut fewFarCells(List<Lane> lanes, Map<String, Integer> served, Map<String, Integer> held,
                                                Stock stock, int minCells) {
        if (minCells <= 0 || lanes.isEmpty() || !stock.anyFar()) return null;
        Map<String, Integer> share = farPart(served, held, stock);
        int n = 0;
        for (int c : share.values()) n += c;
        if (n <= 0 || n >= minCells) return null;
        Map<String, Integer> tile = farPart(lanes, held, stock);
        if (laterFarCells(tile, share) < minCells) return null; // no later far trip: this load takes them now
        Map<String, Integer> drop = new TreeMap<>(tile);
        drop.keySet().retainAll(share.keySet());
        List<Lane> rest = withoutLast(lanes, drop);
        if (rest.isEmpty()) return null; // nothing else left: the trip goes
        return new LeftOut(rest, drop, share);
    }

    /** Far cells of the tile ({@code tile}: item → far part of all the lanes) beyond what a load takes ({@code load}). */
    static int laterFarCells(Map<String, Integer> tile, Map<String, Integer> load) {
        int n = 0;
        for (var e : tile.entrySet()) n += Math.max(0, e.getValue() - load.getOrDefault(e.getKey(), 0));
        return n;
    }

    /** {@link #fewFarCells(List, Map, Map, Stock, int)} for a load of the lanes {@code load} (no batch). */
    public static @Nullable LeftOut fewFarCells(List<Lane> lanes, List<Lane> load, Map<String, Integer> held, Stock stock,
                                                int minCells) {
        return fewFarCells(lanes, cells(load), held, stock, minCells);
    }

    /** {@code lanes} without the last {@code drop.get(item)} cells of each item, in build order; empty lanes dropped. */
    public static List<Lane> withoutLast(List<Lane> lanes, Map<String, Integer> drop) {
        if (drop.isEmpty()) return lanes;
        Map<String, Integer> keep = new HashMap<>();
        cells(lanes).forEach((id, n) -> {
            Integer d = drop.get(id);
            if (d != null && d > 0) keep.put(id, Math.max(0, n - d));
        });
        if (keep.isEmpty()) return lanes;
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

    /**
     * Far regions a load reaches: per item of {@code load} (item → cells) the part beyond {@code held} and the near
     * stock, taken from the far regions in withdrawal order.
     */
    public static Set<Integer> regionsReached(Map<String, Integer> load, Map<String, Integer> held, Stock stock) {
        Set<Integer> out = new LinkedHashSet<>();
        load.forEach((id, n) -> {
            int left = n - Math.max(0, held.getOrDefault(id, 0)) - stock.near(id);
            for (var r : stock.farRegions(id).entrySet()) {
                if (left <= 0) break;
                out.add(r.getKey());
                left -= r.getValue();
            }
        });
        return out;
    }

    /**
     * What a batched load takes along for later lanes.
     *
     * @param cells   item → cells of the upcoming lanes the batch covers (as many as the bot will carry)
     * @param take    item → count withdrawn for them (the load's own cells of these items included)
     * @param regions the far regions the load visits
     * @param lanes   leading lanes of the request whose far items the batch covers
     * @param ofLanes lanes in the request
     */
    public record Batch(Map<String, Integer> cells, Map<String, Integer> take, Set<Integer> regions, int lanes,
                        int ofLanes) {
        public Batch {
            cells = Collections.unmodifiableMap(new TreeMap<>(cells));
            take = Collections.unmodifiableMap(new TreeMap<>(take));
            regions = Set.copyOf(regions);
        }

        /** Cells the batch covers. */
        public int count() {
            int n = 0;
            for (int c : cells.values()) n += c;
            return n;
        }
    }

    /**
     * The batching planner: {@code inner}'s load, and when that load goes to far storage, the load planned again with
     * the far items of the upcoming lanes withdrawn in the same trip (see the class comment). Thread-safe for one
     * engine: the engine runs it on the planner thread and reads {@link #batchOf} on the tick thread afterwards.
     *
     * <p><b>How much.</b> The items: those only far storage supplies for the rest of the tile and whose first far region
     * is one this load visits anyway, at most what the bot can carry of them (held + near stock + that region). The
     * lanes: as many leading lanes' cells of those items as keep room, (1) in this load, for the near items of its
     * first lane (or the part of it the load covers without the batch), and (2) at every later lane of the batch, for
     * that lane's near items plus a margin (a quarter of the load's slots) next to what is left of the batch then. Rule
     * (2) keeps the loads between this one and the next far trip from shrinking to a few cells: in the restock model
     * a batch of everything the rest of the tile needs from far cost up to 20 % more time on 5-wide lanes with the
     * split planner (many loads of a few dozen cells), with the margin at most 2.7 % more and 11 % less on average over
     * 24 tile, lane width and planner cases.
     */
    public static final class Batching implements RestockPlanner {
        private final RestockPlanner inner;
        private final Stock stock;
        private final int margin;
        private final Map<Plan, Batch> batches = Collections.synchronizedMap(new IdentityHashMap<>());

        public Batching(RestockPlanner inner, Stock stock) {
            this(inner, stock, -1);
        }

        /** @param margin the slots kept free at every later lane of the batch; −1 = a quarter of the load's slots */
        Batching(RestockPlanner inner, Stock stock, int margin) {
            this.inner = inner;
            this.stock = stock;
            this.margin = margin;
        }

        public Stock stock() {
            return stock;
        }

        /** The batch of a plan this planner returned; null when that plan is {@code inner}'s own. */
        public @Nullable Batch batchOf(@Nullable Plan p) {
            return p == null ? null : batches.get(p);
        }

        @Override
        public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
            return plan(Request.of(remaining, held, freeSlots, maxStack));
        }

        @Override
        public boolean expensive() {
            return inner.expensive();
        }

        @Override
        public Plan plan(Request r) {
            Plan p0 = inner.plan(r);
            if (!stock.anyFar() || r.remaining().isEmpty()) return p0;
            Map<String, Integer> held = r.held();
            List<Lane> cut0 = RestockLoads.cutLanes(r.remaining(), p0);
            Set<Integer> visited = regionsReached(cells(cut0), held, stock);
            if (visited.isEmpty()) return p0; // no far trip: nothing to batch
            // the items only far storage supplies for the rest of the tile, from a region this load visits anyway:
            // how many of them the bot may carry at most (held + near + that region)
            Map<String, Integer> most = new HashMap<>();
            for (String id : farPart(r.remaining(), held, stock).keySet()) {
                Map<Integer, Integer> regions = stock.farRegions(id);
                if (regions.isEmpty()) continue;
                var first = regions.entrySet().iterator().next();
                if (!visited.contains(first.getKey())) continue;
                most.put(id, Math.max(0, held.getOrDefault(id, 0)) + stock.near(id) + first.getValue());
            }
            if (most.isEmpty()) return p0;
            Slots slots = new Slots(r);
            int capacity = slots.capacity();
            int keep = margin >= 0 ? margin : capacity / 4;
            // (1) this load keeps room for the near items of its first lane (or the part of it p0 covers)
            int fixed = 0;
            for (String id : held.keySet()) if (!most.containsKey(id)) fixed += slots.current(id);
            Map<String, Integer> lane0 = cut0.isEmpty() ? Map.of() : cells(cut0.subList(0, 1));
            for (var e : lane0.entrySet()) {
                if (most.containsKey(e.getKey())) continue;
                fixed += slots.slots(e.getKey(), e.getValue()) - slots.slots(e.getKey(), 0);
            }
            // per lane: the batch items' cells and the near items' compact slots
            List<Map<String, Integer>> laneFar = new ArrayList<>();
            List<Integer> laneNear = new ArrayList<>();
            for (Lane l : r.remaining()) {
                Map<String, Integer> f = new HashMap<>();
                int ns = 0;
                for (var e : cells(List.of(l)).entrySet()) {
                    if (most.containsKey(e.getKey())) f.put(e.getKey(), e.getValue());
                    else ns += (e.getValue() + slots.stack(e.getKey()) - 1) / slots.stack(e.getKey());
                }
                laneFar.add(f);
                laneNear.add(ns);
            }
            Map<String, Integer> acc = new HashMap<>();
            Map<String, Integer> best = null;
            int bestLanes = 0;
            for (int k = 0; k < r.remaining().size(); k++) {
                // (2) every later lane of the batch still fits, with the margin, next to what is left of it then
                boolean fits = true;
                for (int j = 1; j <= k && fits; j++) {
                    int rest = 0;
                    for (String id : most.keySet()) {
                        int n = 0;
                        for (int q = j; q <= k; q++) n += laneFar.get(q).getOrDefault(id, 0);
                        rest += (n + slots.stack(id) - 1) / slots.stack(id);
                    }
                    if (rest + laneNear.get(j) + keep > capacity) fits = false;
                }
                if (!fits) break;
                for (Placement p : r.remaining().get(k).placements()) {
                    if (most.containsKey(p.itemId())) acc.merge(p.itemId(), 1, Integer::sum);
                }
                Map<String, Integer> t = new HashMap<>();
                int farSlots = 0;
                for (var e : most.entrySet()) {
                    int n = Math.min(acc.getOrDefault(e.getKey(), 0), e.getValue());
                    t.put(e.getKey(), n);
                    farSlots += slots.after(e.getKey(), n);
                }
                if (fixed + farSlots > capacity) break;
                best = t;
                bestLanes = k + 1;
            }
            if (best == null) return p0;
            // the items as carried after the batch, and what it withdraws
            Map<String, Integer> held2 = new TreeMap<>(held);
            Map<String, Integer> heldSlots2 = new TreeMap<>(r.heldSlots());
            Map<String, Integer> take = new TreeMap<>();
            Map<String, Integer> cells = new TreeMap<>();
            int free = r.freeSlots();
            for (var e : best.entrySet()) {
                String id = e.getKey();
                int h = Math.max(0, held.getOrDefault(id, 0));
                int n = e.getValue();
                if (n <= h) continue;
                long total = slots.total(id, n);
                held2.put(id, (int) total);
                int s = slots.after(id, n);
                free -= s - slots.current(id);
                heldSlots2.put(id, s);
                take.put(id, (int) (total - h));
                cells.put(id, n);
            }
            if (take.isEmpty() || free < 0) return p0;
            Plan p1 = inner.plan(new Request(r.remaining(), held2, heldSlots2, free, r.maxStack(), r.lastLoadReserve(),
                r.topUp(), r.site()));
            if (p1.lanesLoaded() == 0) return p0;
            // a batch only when it covers cells beyond the load's own
            Map<String, Integer> own = cells(RestockLoads.cutLanes(r.remaining(), p1));
            boolean beyond = false;
            for (var e : cells.entrySet()) if (e.getValue() > own.getOrDefault(e.getKey(), 0)) beyond = true;
            if (!beyond) return p0;
            Map<String, Integer> withdraw = new LinkedHashMap<>(p1.withdraw());
            take.forEach((id, n) -> withdraw.merge(id, n, Integer::sum));
            Plan out = new Plan(withdraw, p1.lanesCovered(), p1.partialCells(), p1.stopAtCut(), p1.rounds(), p1.seconds(),
                p1.flipped());
            batches.put(out, new Batch(cells, take, visited, bestLanes, r.remaining().size()));
            return out;
        }
    }

    /** The slot rules of the restock planners ({@link GreedyRestockPlanner}) for one request. */
    private static final class Slots {
        private final RestockPlanner.Request r;

        Slots(RestockPlanner.Request r) {
            this.r = r;
        }

        int stack(String id) {
            int s = r.maxStack() == null ? 64 : r.maxStack().applyAsInt(id);
            return s > 0 ? s : 64;
        }

        int held(String id) {
            return Math.max(0, r.held().getOrDefault(id, 0));
        }

        /** Slots {@code id} holds now (the request's count, else compact stacks). */
        int current(String id) {
            int h = held(id);
            if (h <= 0) return 0;
            Integer u = r.heldSlots().get(id);
            return u != null && u > 0 ? u : (h + stack(id) - 1) / stack(id);
        }

        /** Every held item's slots plus the free ones. */
        int capacity() {
            int c = Math.max(0, r.freeSlots());
            for (String id : r.held().keySet()) c += current(id);
            return c;
        }

        /** Count of {@code id} carried with {@code n} cells planned (top-up: whole stacks; else held + whole stacks). */
        long total(String id, int n) {
            int h = held(id);
            int s = stack(id);
            if (n <= h) return h;
            return r.topUp() ? (long) ((n + s - 1) / s) * s : h + (long) ((n - h + s - 1) / s) * s;
        }

        /** Compact slots of {@code id} with {@code n} cells planned. */
        int slots(String id, int n) {
            int s = stack(id);
            return (int) ((total(id, n) + s - 1) / s);
        }

        /** Slots {@code id} takes once {@code n} cells of it are carried (held slots kept when nothing is added). */
        int after(String id, int n) {
            return n <= held(id) ? current(id) : slots(id, n);
        }
    }
}
