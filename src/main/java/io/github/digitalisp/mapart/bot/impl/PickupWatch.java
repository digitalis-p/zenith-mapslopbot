package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.bot.WorldView.ItemInFlight;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Are items coming? While a pickup stand waits at the edge it looks at the item entities the server streams near it
 * (items falling and drifting through the sorter): a wanted item that is new or moving is activity, which holds off the
 * stall exit and the re-prime (the owner, 2026-10-14: "it gives up too prematurely"). An item whose stack cannot be read counts
 * for any wanted item. A stack that lies still does not count. Pure.
 */
final class PickupWatch {
    private static final double MOVED = 0.02;

    private final Set<String> wanted;
    private final Map<Integer, double[]> last = new HashMap<>();
    private final Map<String, Integer> seen = new TreeMap<>();
    private long lastActive = Long.MIN_VALUE;

    PickupWatch(Set<String> wanted) {
        this.wanted = wanted;
    }

    /** Takes this tick's item entities near the stand. */
    void update(long now, List<ItemInFlight> flights) {
        Map<Integer, double[]> current = new HashMap<>();
        for (ItemInFlight f : flights) {
            if (f.item() != null && !wanted.contains(f.item())) continue;
            double[] was = last.get(f.entityId());
            double[] at = {f.x(), f.y(), f.z()};
            current.put(f.entityId(), at);
            if (was == null) {
                lastActive = now;
                seen.merge(f.item() == null ? "?" : f.item(), Math.max(1, f.count()), Integer::sum);
            } else if (Math.abs(at[0] - was[0]) + Math.abs(at[1] - was[1]) + Math.abs(at[2] - was[2]) > MOVED) {
                lastActive = now;
            }
        }
        last.clear();
        last.putAll(current);
    }

    /** The last tick a wanted item was new or moving near the stand (Long.MIN_VALUE: never). */
    long lastActive() {
        return lastActive;
    }

    /** Item → count of the stacks that came into view (each entity once); "?" = stack not readable. */
    Map<String, Integer> seen() {
        return seen;
    }
}
