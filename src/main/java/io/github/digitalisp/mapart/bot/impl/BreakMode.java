package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Locale;

/**
 * How blocks are broken ({@code build.breakMode}). Pure.
 *
 * <ul>
 *   <li>{@link #FAST}: the fast nuker. Instant blocks (destroy progress per tick ≥ 1, e.g. terracotta with Efficiency V
 *       and Haste II) get up to {@code build.nukeStartsPerTick} STARTs per tick, everything else the vanilla client
 *       timing; a large flat set of targets is swept in lanes, breaking while walking ({@link NukeRun}). The plugin
 *       sends the dig packets itself.</li>
 *   <li>{@link #DOUBLE}: the 2b2t doublebreak. Pairs of same-tool blocks in reach of one stand position: one left to the
 *       server's delayed destroy while the other is mined ({@link BreakRun}, {@link DigClient}, {@link BreakPairs}).
 *       The plugin sends the dig packets itself.</li>
 *   <li>{@link #SINGLE}: one block at a time through ZenithProxy's left click, as before 2026-10-11. The kill switch.</li>
 * </ul>
 */
public enum BreakMode {
    FAST, DOUBLE, SINGLE;

    /** {@code build.breakMode}, case-insensitive; unknown or null = FAST (the default). */
    public static BreakMode of(@Nullable String s) {
        if (s == null) return FAST;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "SINGLE", "VANILLA", "OFF" -> SINGLE;
            case "DOUBLE", "DOUBLEBREAK" -> DOUBLE;
            default -> FAST;
        };
    }

    public static BreakMode of(MapArtConfig.BuildCfg b) {
        return of(b.breakMode);
    }
}
