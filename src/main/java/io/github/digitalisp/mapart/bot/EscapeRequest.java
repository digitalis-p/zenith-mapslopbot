package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Pos;

import java.util.Set;

/**
 * One rung of the setback escape ladder: the server kept setting the bot back
 * at ({@code x}, {@code y}, {@code z}) (its feet position when the burst of setbacks was detected) and the engine's
 * back-off stood still; now the bot gets itself out before the phase carries on.
 *
 * @param x     feet position of the burst (real coordinates)
 * @param y     feet position of the burst
 * @param z     feet position of the burst
 * @param rung  1-based rung within the escalation window: 1 is the first burst, the ladder ends with a pause after
 *              {@code safety.setbackBackoffMaxPerWindow} rungs
 * @param avoid feet cells where bursts happened before (this one included): the bot neither escapes to nor through
 *              them, nor within one block of them
 */
public record EscapeRequest(double x, double y, double z, int rung, Set<Pos> avoid) {
    public EscapeRequest {
        avoid = Set.copyOf(avoid);
    }

    /** The feet cell of the burst (ZenithProxy's convention: y + 0.1251 for soul sand). */
    public Pos cell() {
        return new Pos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z));
    }
}
