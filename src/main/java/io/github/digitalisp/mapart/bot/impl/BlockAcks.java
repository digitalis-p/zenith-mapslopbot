package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * The block-changed-acks the server sent on this connection ({@code ClientboundBlockChangedAckPacket}), for
 * {@link PlaceTracker}: the latest sequence, and the recent sequences acked exactly (an ack carrying a click's own
 * sequence answers that click; one carrying a higher sequence only covers it). Written by the map-art module's packet
 * handler (on the client tick loop, after the block updates that preceded the ack on the wire), read on the client
 * tick thread. Reset on disconnect: ZenithProxy starts a new prediction counter with a new chunk cache.
 */
public final class BlockAcks {
    /** Exact ack sequences kept: far more than the acks of one confirm window (one per server tick or cancel). */
    static final int KEPT = 1024;

    private static volatile int latest = -1;
    private static final Set<Integer> exact = new HashSet<>();
    private static final ArrayDeque<Integer> order = new ArrayDeque<>();

    private BlockAcks() { }

    /** The server acknowledged block changes up to {@code sequence}. */
    public static void record(int sequence) {
        latest = sequence;
        synchronized (exact) {
            if (!exact.add(sequence)) return;
            order.addLast(sequence);
            if (order.size() > KEPT) exact.remove(order.removeFirst());
        }
    }

    /** Forget the connection's acks (disconnect, new login). */
    public static void reset() {
        latest = -1;
        synchronized (exact) {
            exact.clear();
            order.clear();
        }
    }

    /** Highest acknowledged sequence, -1 if none on this connection. */
    public static int latest() {
        return latest;
    }

    /** An ack carrying exactly {@code sequence} arrived on this connection (one of the last {@link #KEPT}). */
    public static boolean ackedExactly(int sequence) {
        synchronized (exact) {
            return exact.contains(sequence);
        }
    }
}
