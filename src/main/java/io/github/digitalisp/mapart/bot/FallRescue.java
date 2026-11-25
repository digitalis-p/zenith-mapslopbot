package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Pos;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A self-rescue after a fall: get from {@code at} back onto dry ground with the feet
 * at {@code floorY} or above (the platform's art layer; {@link Integer#MIN_VALUE}: any dry ground) within
 * {@code maxTicks}. {@code why}: what the engine saw ("feet at y170.0, 3.0 below the floor of platform soulsand").
 * {@code holdTicks}: the bot holds its place this long before it climbs (a clear's flying machines still move over the
 * channels, or the wait before another try). {@code own}: blocks an earlier try of the same fall placed and left
 * standing (cell → block id): this try treats them as its own (it may stand on them, and breaks them again).
 * {@code leftBlocks}: told (on the client tick thread) when the rescue got out but left blocks of its own in the world it
 * could not break again, as "x,y,z minecraft:netherrack; ...". {@code ended}: told when the try ends, however it ends
 * ({@link Ended}).
 */
public record FallRescue(Pos at, int floorY, int maxTicks, String why, int holdTicks, Map<Pos, String> own,
                         Consumer<String> leftBlocks, Consumer<Ended> ended) {
    /** No floor level to get back to: dry ground anywhere will do (the bot was in water off any platform). */
    public static final int ANY_LEVEL = Integer.MIN_VALUE;

    public FallRescue {
        own = Map.copyOf(own);
    }

    /**
     * How a try ended: its own blocks still standing (cell → id, empty when none) and the highest feet level at which
     * the bot stood on something during the try ({@link #ANY_LEVEL} when it never did). A bot bobbing in water is no
     * progress; one standing on its first block is.
     */
    public record Ended(Map<Pos, String> own, int standY) {
        public Ended {
            own = Map.copyOf(own);
        }
    }

    public FallRescue(Pos at, int floorY, int maxTicks, String why) {
        this(at, floorY, maxTicks, why, 0, Map.of(), s -> { }, e -> { });
    }
}
