package io.github.digitalisp.mapart.engine;

import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import static com.zenith.Globals.CONFIG;

/**
 * Plugin load ({@code map.raiseZenithMapCache}, default on): raises ZenithProxy's {@code debug.server.cache.maxCachedMaps}
 * to {@value #RAISED} when it is below {@value #RAISE_BELOW}. ZenithProxy's MapDataCache skips every NEW map id once it
 * holds more maps than that (default 128), and the hundreds of framed maps at the base fill it at login: on 2b2t
 * (2026-10-11) it kept none of the bot's map, and a player joining the proxy saw no picture either. The plugin's own
 * capture ({@code bot.impl.MapStore}) does not depend on it; the raise is for the fallback and for players who join.
 *
 * <p>Cost: about 16 KB per map the server actually sends (a few hundred at the base: a few MB), at most 64 MB at 4096.
 * The value lands in ZenithProxy's {@code config.json} at its next save; {@code debug maxCachedMaps 128} undoes it (with
 * the toggle off). A raise mid-session only helps after a reconnect: entries made from a later patch hold zeros elsewhere.
 */
public final class ZenithMapCacheLimit {
    /** Limits below this are raised. */
    public static final int RAISE_BELOW = 2048;
    /** ... to this. */
    public static final int RAISED = 4096;

    private ZenithMapCacheLimit() { }

    /** The limit to set, or empty to leave {@code current} as it is. Pure. */
    public static OptionalInt raisedLimit(boolean enabled, int current) {
        return enabled && current < RAISE_BELOW ? OptionalInt.of(RAISED) : OptionalInt.empty();
    }

    /** Applies {@link #raisedLimit} through {@code get}/{@code set} and logs the change once. True if it raised. */
    public static boolean apply(boolean enabled, IntSupplier get, IntConsumer set, Consumer<String> log) {
        int current = get.getAsInt();
        OptionalInt next = raisedLimit(enabled, current);
        if (next.isEmpty()) return false;
        set.accept(next.getAsInt());
        log.accept("Map art: raised ZenithProxy's map cache limit (debug maxCachedMaps) from " + current + " to "
            + next.getAsInt() + ": it skips new map ids once full, and framed maps fill " + current + " quickly"
            + " (map.raiseZenithMapCache)");
        return true;
    }

    /** Production: ZenithProxy's live {@code CONFIG}. */
    public static boolean applyToZenith(boolean enabled, Consumer<String> log) {
        return apply(enabled, () -> CONFIG.debug.server.cache.maxCachedMaps,
            v -> CONFIG.debug.server.cache.maxCachedMaps = v, log);
    }
}
