package io.github.digitalisp.mapart.coord;

import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The engine's writes to the shared stock index: whoever opened a container writes what it saw, compare-and-set on the
 * version it last saw ({@link Coordination#putStock}). A write refused with {@code cas_failed} (another bot wrote the
 * entry meanwhile) is tried again with the newer version while this reading is still the newer one (its
 * {@code scannedAt} after the entry's), at most {@link #MAX_TRIES} times; an older reading is dropped, the newer entry
 * stands. Other errors drop the write (the coordinator was unreachable: the next opening writes again).
 *
 * <p>Tick thread only; {@link #tick} polls the writes in flight. With {@link LocalCoordination} every write completes at
 * once and nothing is kept.
 */
public final class SharedStock {
    static final int MAX_TRIES = 3;

    private final Coordination coord;
    private final List<Write> inFlight = new ArrayList<>();

    private record Write(StockEntry entry, Pending<Long> pending, int tries) { }

    public SharedStock(Coordination coord) {
        this.coord = coord;
    }

    /** A container was read while open: its snapshot goes to the shared index. */
    public void record(Pos container, Map<String, Integer> items, int freeSlots, int totalSlots, long now) {
        put(new StockEntry(container, items, freeSlots, totalSlots, now), 1);
    }

    private void put(StockEntry e, int tries) {
        Pending<Long> p = coord.putStock(e, coord.stockVersion(e.container()));
        if (p.done() && p.error() == null) return;
        inFlight.add(new Write(e, p, tries));
    }

    /** Every tick: settles the writes in flight. */
    public void tick() {
        if (inFlight.isEmpty()) return;
        List<Write> todo = new ArrayList<>(inFlight);
        inFlight.clear();
        for (Write w : todo) {
            if (!w.pending().done()) {
                inFlight.add(w);
                continue;
            }
            String err = w.pending().error();
            if (err == null) continue;
            if ("cas_failed".equals(err) && w.tries() < MAX_TRIES && newer(w.entry())) put(w.entry(), w.tries() + 1);
        }
    }

    /** Writes not settled yet (tests). */
    public int inFlight() {
        return inFlight.size();
    }

    /** {@code e} was read after the snapshot the index holds for its container (or it holds none). */
    private boolean newer(StockEntry e) {
        StockIndex st = coord.stock();
        synchronized (st) {
            StockIndex.Snapshot s = st.containers.get(e.container().key());
            return s == null || s.scannedAt <= e.scannedAt();
        }
    }
}
