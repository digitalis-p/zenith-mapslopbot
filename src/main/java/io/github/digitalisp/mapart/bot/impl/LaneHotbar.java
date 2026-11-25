package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/** Pure pre-lane layout. Distinct materials by next use, then second stacks by remaining demand. */
public final class LaneHotbar {
    private LaneHotbar() { }

    /** Only eligible slots are passed: food, reserved slots and tools never enter the layout. */
    public record Stack(int slot, @Nullable String item, int count) { }
    public record Move(String item, int slot) { }

    /** One move, recomputed after its request and server fence finish; null means the layout is ready. */
    public static @Nullable Move next(List<String> cells, List<Stack> slots, ToIntFunction<String> mainCount) {
        return next(cells, slots, mainCount, slot -> false);
    }

    /**
     * {@link #next(List, List, ToIntFunction)} that never moves into a {@code pinned} slot ({@code build.laneLayoutPin}:
     * the slots this layout already filled). Without pins the plan can cycle: whether an item wants a second stack
     * depends on the average size of its hotbar stacks, which every swap with the main inventory changes, so two items
     * can evict each other's second stack for ever (live 2026-10-21 00:55, ABD [9,5]: lane 11 stood in APPROACH with
     * no log line through 4 task timeouts of 530 s each; each swap changed the hotbar, so the lane watchdog never
     * tripped). Every move pins its slot, so a layout with pins ends after at most one move per slot.
     */
    public static @Nullable Move next(List<String> cells, List<Stack> slots, ToIntFunction<String> mainCount,
                                      IntPredicate pinned) {
        Map<String, Integer> demand = new LinkedHashMap<>();
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String item = ItemIds.material(cells.get(i));
            demand.merge(item, 1, Integer::sum);
            first.putIfAbsent(item, i);
        }
        Map<String, Integer> have = new HashMap<>();
        Map<String, Integer> amounts = new HashMap<>();
        for (Stack s : slots) if (s.item() != null && s.count() > 0) {
            have.merge(s.item(), 1, Integer::sum);
            amounts.merge(s.item(), s.count(), Integer::sum);
        }
        Map<String, Integer> want = new LinkedHashMap<>();
        int room = slots.size();
        // Keeping the nearest distinct uses is Belady's initial cache; farthest uses wait in main inventory.
        for (String item : demand.keySet()) {
            if (room == 0) break;
            if (have.getOrDefault(item, 0) == 0 && mainCount.applyAsInt(item) <= 0) continue;
            want.put(item, 1);
            room--;
        }
        List<String> popular = new ArrayList<>(want.keySet());
        popular.sort(Comparator.<String>comparingInt(demand::get).reversed().thenComparingInt(first::get));
        for (String item : popular) {
            if (room == 0) break;
            int n = have.getOrDefault(item, 0);
            int oneStack = n == 0 ? 64 : amounts.get(item) / n;
            if (demand.get(item) <= oneStack || (n < 2 && mainCount.applyAsInt(item) <= 0)) continue;
            want.put(item, 2);
            room--;
        }
        for (int copies = 1; copies <= 2; copies++) for (var w : want.entrySet()) {
            if (have.getOrDefault(w.getKey(), 0) >= Math.min(copies, w.getValue())
                || mainCount.applyAsInt(w.getKey()) <= 0) continue;
            Stack target = null;
            int farthest = -1;
            for (Stack s : slots) {
                if (pinned.test(s.slot())) continue;
                if (s.item() == null || s.count() <= 0) return new Move(w.getKey(), s.slot());
                if (have.getOrDefault(s.item(), 0) <= want.getOrDefault(s.item(), 0)) continue;
                int next = first.getOrDefault(s.item(), Integer.MAX_VALUE);
                if (target == null || next > farthest) {
                    target = s;
                    farthest = next;
                }
            }
            if (target != null) return new Move(w.getKey(), target.slot());
        }
        return null;
    }
}
