package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which block positions the server sent a block update for, and when (a counter of block updates), for
 * {@link PlaceTracker}'s answers: vanilla {@code handleUseItemOn} answers every click it handles with a
 * {@code ClientboundBlockUpdatePacket} for the clicked block and one for the cell next to the clicked face, changed or
 * not, before its ack; a click Paper's click limiter drops gets neither. A click's mark ({@link #mark}, taken just before
 * it is sent) and a later update for its cell ({@link #since}) tell the two apart.
 *
 * <p>Written by the map-art module's packet handler inline on the connection's thread, so an update is recorded before
 * the ack that follows it on the wire is handed to the client tick loop ({@link BlockAcks}); read on the client tick
 * thread. Keeps the last {@link #KEPT} positions: far more than the updates of one confirm window near the base.
 */
public final class BlockEchoes {
    static final int KEPT = 4096;

    private static long count;
    private static final Map<Pos, Long> at = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Pos, Long> eldest) {
            return size() > KEPT;
        }
    };

    private BlockEchoes() { }

    /** The server sent a block update for (x, y, z). */
    public static synchronized void record(int x, int y, int z) {
        Pos p = new Pos(x, y, z);
        at.remove(p); // re-insert: newest last, evicted last
        at.put(p, ++count);
    }

    /** Block updates received so far (a mark to pass to {@link #since}); never decreases. */
    public static synchronized long mark() {
        return count;
    }

    /** A block update for {@code pos} arrived after {@code mark} was taken. */
    public static synchronized boolean since(Pos pos, long mark) {
        Long t = at.get(pos);
        return t != null && t > mark;
    }

    /** Forget the positions (disconnect, new login); marks stay comparable. */
    public static synchronized void reset() {
        at.clear();
    }
}
