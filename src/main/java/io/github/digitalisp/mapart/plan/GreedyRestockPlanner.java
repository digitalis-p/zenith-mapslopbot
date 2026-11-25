package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Fills the inventory greedily along the build order: whole lanes while they fit, then the first cells of the next
 * lane, in placement order, until the next cell would need a slot that is not there.
 *
 * <p><b>Capacity</b> = {@code freeSlots} (empty slots usable for materials) + the slots the {@code held} materials
 * occupy now. Every held item keeps its slots whether or not the load uses it.
 *
 * <p><b>Slots per item</b> with {@code n} cells planned, {@code h} held and stack size {@code s}: withdrawals are
 * whole-stack shift-clicks ({@code TransferPlanner}), merged into a held partial stack first. So
 * {@code ceil(h / s)} slots while {@code n <= h}, else {@code ceil((h + roundUp(n - h, s)) / s)}: the held partial
 * stack is topped up and the rest of the last withdrawn stack takes a slot of its own. Cells that fit into that
 * rounded-up stack cost no further slot, so the greedy fill uses them.
 *
 * <p><b>With top-up</b> ({@link Request#topUp}, {@code WithdrawMode.TOP_UP}: the held partial stack is filled from a
 * full container stack and the rest goes back) an item ends with {@code roundUp(n, s)} in {@code ceil(n / s)} slots,
 * and the withdrawal asked for is that target minus what is held.
 */
public final class GreedyRestockPlanner implements RestockPlanner {
    private static final int DEFAULT_STACK = 64;

    @Override
    public Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack) {
        return plan(remaining, held, freeSlots, maxStack, false);
    }

    /** As the default {@link RestockPlanner#plan(Request)} (last-load room), with the request's top-up rule. */
    @Override
    public Plan plan(Request r) {
        Plan p = plan(r.remaining(), r.held(), r.freeSlots(), r.maxStack(), r.topUp());
        if (r.lastLoadReserve() > 0 && p.lanesCovered() >= r.remaining().size()) {
            p = plan(r.remaining(), r.held(), Math.max(0, r.freeSlots() - r.lastLoadReserve()), r.maxStack(), r.topUp());
        }
        return p;
    }

    private Plan plan(List<Lane> remaining, Map<String, Integer> held, int freeSlots, ToIntFunction<String> maxStack,
                      boolean topUp) {
        Load load = new Load(held == null ? Map.of() : held, maxStack, topUp);
        int cap = Math.max(0, freeSlots) + load.slots;
        int covered = 0;
        for (Lane lane : remaining) {
            Map<String, Integer> cells = new HashMap<>();
            for (Placement p : lane.placements()) if (p.itemId() != null) cells.merge(p.itemId(), 1, Integer::sum);
            if (load.slotsWith(cells) > cap) break;
            load.add(cells);
            covered++;
        }
        int partial = 0;
        if (covered < remaining.size()) {
            for (Placement p : remaining.get(covered).placements()) {
                if (p.itemId() != null) {
                    Map<String, Integer> one = Map.of(p.itemId(), 1);
                    if (load.slotsWith(one) > cap) break;
                    load.add(one);
                }
                partial++;
            }
        }
        return new Plan(load.withdraw(), covered, partial);
    }

    /** Slots occupied after loading {@code need} on top of {@code held} (every held item counts). */
    static int slots(Map<String, Integer> need, Map<String, Integer> held, ToIntFunction<String> maxStack) {
        Load load = new Load(held, maxStack, false);
        return load.slotsWith(need);
    }

    /** Cells planned per item on top of what is held, and the slots that takes. */
    private static final class Load {
        private final Map<String, Integer> held;
        private final ToIntFunction<String> maxStack;
        private final boolean topUp;
        private final Map<String, Integer> stacks = new HashMap<>();
        private final Map<String, Integer> need = new LinkedHashMap<>();
        int slots;

        Load(Map<String, Integer> held, ToIntFunction<String> maxStack, boolean topUp) {
            this.held = held;
            this.maxStack = maxStack;
            this.topUp = topUp;
            for (String item : held.keySet()) slots += slotsOf(item, 0);
        }

        private int stack(String item) {
            return stacks.computeIfAbsent(item, i -> {
                int s = maxStack == null ? DEFAULT_STACK : maxStack.applyAsInt(i);
                return s > 0 ? s : DEFAULT_STACK;
            });
        }

        /** Count of {@code item} carried after the load with {@code n} cells planned. */
        private long total(String item, int n) {
            int h = Math.max(0, held.getOrDefault(item, 0));
            int s = stack(item);
            if (n <= h) return h;
            return topUp ? (long) ceilDiv(n, s) * s : h + (long) ceilDiv(n - h, s) * s;
        }

        /** Slots {@code item} occupies with {@code n} cells planned. */
        private int slotsOf(String item, int n) {
            int s = stack(item);
            return (int) ((total(item, n) + s - 1) / s);
        }

        int slotsWith(Map<String, Integer> more) {
            int total = slots;
            for (var e : more.entrySet()) {
                int n = need.getOrDefault(e.getKey(), 0);
                total += slotsOf(e.getKey(), n + e.getValue()) - slotsOf(e.getKey(), n);
            }
            return total;
        }

        void add(Map<String, Integer> more) {
            slots = slotsWith(more);
            more.forEach((item, n) -> need.merge(item, n, Integer::sum));
        }

        /** Per item: the cells planned beyond what is held; with top-up the whole stack-aligned target beyond it. */
        Map<String, Integer> withdraw() {
            Map<String, Integer> w = new LinkedHashMap<>();
            need.forEach((item, n) -> {
                int h = Math.max(0, held.getOrDefault(item, 0));
                long left = topUp ? total(item, n) - h : n - h;
                if (n > h && left > 0) w.put(item, (int) left);
            });
            return w;
        }

        private static int ceilDiv(int a, int b) {
            return (a + b - 1) / b;
        }
    }
}
