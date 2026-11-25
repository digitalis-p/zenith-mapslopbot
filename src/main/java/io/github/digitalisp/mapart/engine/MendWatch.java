package io.github.digitalisp.mapart.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The mend watchdog's clock (pure, unit-tested): since when each Mending item
 * ("tool:minecraft:netherite_pickaxe", "armor:8") has been due for mending without a successful mend. An item overdue
 * ({@code build.mendWatchdogMinutes}) is mended at the next safe point whatever held its mend; each overdue episode is
 * reported once.
 */
final class MendWatch {
    private final Map<String, Long> dueSince = new HashMap<>();
    private final Set<String> reported = new HashSet<>();

    /** {@code key} is due for mending now: its clock starts unless it already runs. */
    void due(String key, long tick) {
        dueSince.putIfAbsent(key, tick);
    }

    /** {@code key} is not due (mended, gone): the clock stops and a new episode reports again. */
    void fine(String key) {
        dueSince.remove(key);
        reported.remove(key);
    }

    /** A mend of {@code key} raised it: the clock starts again from now (it may still be due). */
    void mended(String key, long tick) {
        if (dueSince.containsKey(key)) dueSince.put(key, tick);
        reported.remove(key);
    }

    /** Due for {@code limitTicks} or more without a successful mend ({@code limitTicks} 0 = never). */
    boolean overdue(String key, long tick, long limitTicks) {
        Long s = dueSince.get(key);
        return limitTicks > 0 && s != null && tick - s >= limitTicks;
    }

    /** Ticks {@code key} has been due, or 0. */
    long dueFor(String key, long tick) {
        Long s = dueSince.get(key);
        return s == null ? 0 : tick - s;
    }

    /** True the first time an overdue episode of {@code key} is reported. */
    boolean report(String key) {
        return reported.add(key);
    }

    /** Stops every clock whose key starts with {@code prefix} and is not in {@code present} (an item no longer carried). */
    void retain(String prefix, Set<String> present) {
        dueSince.keySet().removeIf(k -> k.startsWith(prefix) && !present.contains(k));
        reported.removeIf(k -> k.startsWith(prefix) && !present.contains(k));
    }

    void clear() {
        dueSince.clear();
        reported.clear();
    }
}
