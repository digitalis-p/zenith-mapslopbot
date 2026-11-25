package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Standing lookahead: refill before exhaustion, without evicting any of the next 40 cells' materials. */
public final class LanePrefetch {
    public static final int CELLS = 40;

    /** Available count excludes unsettled/prepared clicks; replaceable also excludes reachable and personal stacks. */
    public record Stack(int slot, @Nullable String item, int available, boolean replaceable) { }
    public record Move(String item, int slot) { }

    private LanePrefetch() { }

    /** Earliest shortage in the horizon, then Belady's farthest next use among safe targets. */
    public static @Nullable Move next(List<String> cells, List<Stack> slots, int held,
                                     ToIntFunction<String> mainCount, boolean evictFuture) {
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) first.putIfAbsent(ItemIds.material(cells.get(i)), i);
        Map<String, Integer> available = new HashMap<>();
        for (Stack s : slots) if (s.item() != null)
            available.merge(ItemIds.material(s.item()), Math.max(0, s.available()), Integer::sum);
        Map<String, Integer> demand = new HashMap<>();
        for (int i = 0; i < Math.min(CELLS, cells.size()); i++) {
            String item = ItemIds.material(cells.get(i));
            int n = demand.merge(item, 1, Integer::sum);
            if (n <= available.getOrDefault(item, 0) || mainCount.applyAsInt(item) <= 0) continue;
            Stack target = null;
            int far = -1;
            for (Stack s : slots) {
                if (s.slot() == held || !s.replaceable()
                    || (s.item() != null && item.equals(ItemIds.material(s.item())))) continue;
                int use = s.item() == null ? Integer.MAX_VALUE
                    : first.getOrDefault(ItemIds.material(s.item()), Integer.MAX_VALUE);
                if (use < CELLS || (!evictFuture && use != Integer.MAX_VALUE)) continue;
                // Empty slots first; otherwise the farthest next use, stable slot order on ties.
                if (s.item() == null) return new Move(item, s.slot());
                if (target == null || use > far) { target = s; far = use; }
            }
            if (target != null) return new Move(item, target.slot());
        }
        return null;
    }
}
