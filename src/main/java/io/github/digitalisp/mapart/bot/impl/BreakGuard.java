package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.zenith.Globals.CONFIG;

/**
 * Lets ZenithProxy's pathfinder break the art blocks that can wall the bot in, and nothing else, while held: the
 * pathfinder's {@code allowBreak} stays false and {@link #SEAL_BLOCKS} go into its {@code allowBreakAnyway} list, at a
 * cost ({@code blockBreakAdditionalCost} at least {@link #BREAK_COST}) that keeps a break the last way out. The last
 * resort of a walk that failed from an area sealed by pointed dripstone ({@link ZenithTask#walkBreaking}): only
 * Baritone knows the ways it can take. Live 2026-10-13, remu [2,2]: the plugin's own walk grid kept seeing ways out
 * that Baritone refused (candles, a ledge off the tile), the bot stayed BLOCKED for an hour; "pathfinder goto" with
 * allowBreak walked it to the storage in 11 s.
 *
 * <p>Never {@code allowBreak} itself: live 2026-10-13 23:43 (soul sand), a walk to a chest whose goal it
 * could not reach failed, the break retry took over with allowBreak on, and Baritone broke storage chests to climb
 * to the goal. The pathfinder skips every block outside {@code allowBreakAnyway} while {@code allowBreak} is false
 * (CalculationContext.breakCostMultiplierAt: 1,000,000), so a chest, a sorter or the platform cannot be broken.
 * Walker still refuses every path into a no-go zone (the flying machines, the stations). Reference-counted like
 * {@link FallGuard}; the last holder restores the values it found.
 *
 * <p>Risk, as for {@link FallGuard}: a config save while held followed by a crash keeps the values until changed by
 * hand ({@code client.extra.pathfinder.allowBreakAnyway} empty, {@code blockBreakAdditionalCost} 2.0 in config.json).
 */
public final class BreakGuard {
    private BreakGuard() { }

    /**
     * Cost added per broken block while held (ZenithProxy's default 2.0), so a break is worth a few blocks of walking:
     * a search that ends within the pathfinder's time limit (live remu [2,2]: at 1000 the walk found no path in time;
     * "pathfinder goto" at 2.0 did in 0.4 s).
     */
    static final double BREAK_COST = 10;

    /**
     * The only blocks a breaking walk may break (registry names, no namespace): art blocks the pathfinder can neither
     * walk through nor onto, which wall the bot in on the art layer. Never a container, a full block or a machine part.
     */
    static final Set<String> SEAL_BLOCKS = Set.of("pointed_dripstone", "decorated_pot", "lantern", "soul_lantern",
        "candle", "white_candle", "orange_candle", "magenta_candle", "light_blue_candle", "yellow_candle", "lime_candle",
        "pink_candle", "gray_candle", "light_gray_candle", "cyan_candle", "purple_candle", "blue_candle", "brown_candle",
        "green_candle", "red_candle", "black_candle");

    private record Saved(boolean allowBreak, List<String> breakAnyway, double breakCost) { }

    private static final RefCountedOverride<Saved> OVERRIDE = new RefCountedOverride<>(
        () -> {
            var p = CONFIG.client.extra.pathfinder;
            return new Saved(p.allowBreak, new ArrayList<>(p.allowBreakAnyway), p.blockBreakAdditionalCost);
        },
        () -> {
            var p = CONFIG.client.extra.pathfinder;
            p.allowBreak = false;
            if (!p.allowBreakAnyway.containsAll(SEAL_BLOCKS)) p.allowBreakAnyway.addAll(SEAL_BLOCKS);
            if (p.blockBreakAdditionalCost < BREAK_COST) p.blockBreakAdditionalCost = BREAK_COST;
        },
        saved -> {
            var p = CONFIG.client.extra.pathfinder;
            p.allowBreak = saved.allowBreak();
            p.allowBreakAnyway.clear();
            p.allowBreakAnyway.addAll(saved.breakAnyway());
            p.blockBreakAdditionalCost = saved.breakCost();
        });

    /** Breaking of {@link #SEAL_BLOCKS} allowed (and costly) until the handle is released. */
    public static RefCountedOverride.Handle acquire() {
        return OVERRIDE.acquire();
    }

    /** Re-applies it while held. */
    public static void enforce() {
        OVERRIDE.enforce();
    }

    /** Holders right now (0: the pathfinder's own values are in force). */
    public static int holders() {
        return OVERRIDE.holders();
    }
}
