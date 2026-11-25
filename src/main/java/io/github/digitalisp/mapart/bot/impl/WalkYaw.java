package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * The short turn for Baritone's rotations during the plugin's walks ({@code build.walkShortTurns}). Pure.
 *
 * <p>ZenithProxy 3.7.2's Bot turns to a requested yaw by the difference wrapped once into [-180, 180]
 * ({@code Bot.requestMovement}): from a yaw beyond ±180 a request more than 540° away is not brought into range, and
 * the bot jumps by more than 180° in one tick. Baritone asks for yaws in about [-360, 360] while the bot's own yaw drifts
 * (each relative request adds to it; local run: 283.9 → 291.2 → 430.0, then a request of -260.9 jumped to 99.1, a turn
 * of -330.9). A jump of more than 320° that lands within ±360 after a small turn is Grim's AimModulo360 (a client that
 * wraps its rotation): 3 flags per 9 restock trips locally, with and without sprint, and the same jump in the 2b2t
 * replay of 18:05. {@link #shortTurn} gives the same heading by the turn of at most 180°.
 */
public final class WalkYaw {
    private WalkYaw() { }

    /** The turn ZenithProxy's Bot makes from {@code current} towards a request of {@code requested} (one wrap). */
    public static float botTurn(float current, float requested) {
        float d = requested - current;
        if (d > 180) d -= 360;
        else if (d < -180) d += 360;
        return d;
    }

    /**
     * The yaw to request instead of {@code requested} so that the bot turns the short way, or null when the Bot's own
     * wrap already does (|turn| ≤ 180).
     */
    public static @Nullable Float shortTurn(float current, float requested) {
        if (Math.abs(botTurn(current, requested)) <= 180f) return null;
        return Aim.nearestYaw(current, requested);
    }
}
