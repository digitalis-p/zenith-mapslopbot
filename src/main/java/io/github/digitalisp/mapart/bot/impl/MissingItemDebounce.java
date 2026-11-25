package io.github.digitalisp.mapart.bot.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * Debounces "missing item" for the lane placer. 2b2t answers a placement with a slot update and then a full
 * inventory update, so the cached inventory can show a stack as gone for a moment (the reason the 2b2t printer
 * drops those slot updates). An item only counts as missing after its count was zero on {@link #TICKS}
 * consecutive ticks. Pure; client tick thread only.
 */
final class MissingItemDebounce {
    /** Consecutive ticks an item must read zero before placements needing it are skipped. */
    static final int TICKS = 5;

    private record Zero(long since, long last) { }

    private final Map<String, Zero> zero = new HashMap<>();

    /** Records this tick's {@code count} of {@code item}; true once it has been zero for {@link #TICKS} ticks in a row. */
    boolean missing(String item, int count, long now) {
        if (count > 0) {
            zero.remove(item);
            return false;
        }
        Zero z = zero.get(item);
        if (z == null || now - z.last() > 1) z = new Zero(now, now);
        else z = new Zero(z.since(), now);
        zero.put(item, z);
        return now - z.since() + 1 >= TICKS;
    }
}
