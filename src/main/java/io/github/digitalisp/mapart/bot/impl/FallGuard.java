package io.github.digitalisp.mapart.bot.impl;

import static com.zenith.Globals.CONFIG;

/**
 * Raises ZenithProxy's pathfinder drop limit ({@code client.extra.pathfinder.maxFallHeightNoWater}, 3 by default)
 * while a {@link HopDownTask} paths, so Baritone may plan the drop from a raised stand (soul sand: the button cross,
 * feet at the art layer + 6) onto the floor. Reference-counted like {@link PathingGuard}; the last holder restores
 * the value it found. Never lowers the setting.
 *
 * <p>Risk, as for {@link PathingGuard}: the value lives in ZenithProxy's global config; a config save during the hop
 * followed by a crash keeps the raised value until changed by hand ({@code pathfinder maxFallHeightNoWater 3}).
 */
public final class FallGuard {
    private FallGuard() { }

    /** The drop the current holders want (the largest asked for). */
    private static volatile int wanted;

    private static final RefCountedOverride<Integer> OVERRIDE = new RefCountedOverride<>(
        () -> CONFIG.client.extra.pathfinder.maxFallHeightNoWater,
        () -> {
            var p = CONFIG.client.extra.pathfinder;
            if (p.maxFallHeightNoWater < wanted) p.maxFallHeightNoWater = wanted;
        },
        saved -> CONFIG.client.extra.pathfinder.maxFallHeightNoWater = saved);

    /**
     * Holds a drop limit of at least {@code maxDrop} blocks until the handle is released. {@link #effective} gives
     * the value Baritone's limit must have for a fall of {@code maxDrop} blocks.
     */
    public static RefCountedOverride.Handle acquire(int maxDrop) {
        synchronized (FallGuard.class) {
            if (OVERRIDE.holders() == 0) wanted = 0;
            wanted = Math.max(wanted, effective(maxDrop));
        }
        return OVERRIDE.acquire();
    }

    /** Re-applies the raised limit while held. */
    public static void enforce() {
        OVERRIDE.enforce();
    }

    /**
     * Baritone's limit for a drop of {@code drop} blocks from the stand's feet level to the floor's feet level:
     * ZenithProxy's {@code MovementDescend} refuses a fall when the blocks fallen, counted from the feet level to the
     * block landed on, exceed {@code maxFallHeightNoWater + 1}; that count is the drop + 1, so the limit is the drop.
     * Pure, unit-tested.
     */
    static int effective(int drop) {
        return Math.max(3, drop);
    }
}
